package io.bluetape4k.leader.exposed.jdbc

import io.bluetape4k.leader.AopScopeAccess
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderLeaseAutoExtender
import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider
import io.bluetape4k.leader.exposed.jdbc.internal.ExposedJdbcBackendErrorClassifier
import io.bluetape4k.leader.exposed.jdbc.internal.ExposedJdbcLockExtendDelegate
import io.bluetape4k.leader.exposed.jdbc.lock.ExposedJdbcLock
import io.bluetape4k.leader.exposed.jdbc.lock.ExposedJdbcSchemaInitializer
import io.bluetape4k.leader.exposed.jdbc.lock.validateExposedLockName
import io.bluetape4k.leader.history.LeaderHistoryKey
import io.bluetape4k.leader.history.LeaderLockHistoryRecord
import io.bluetape4k.leader.history.SafeLeaderHistoryRecorder
import io.bluetape4k.leader.internal.CompositeBackendErrorClassifier
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CancellationException
import org.jetbrains.exposed.v1.jdbc.Database
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * `ExposedJdbcLeaderElector`는 Exposed database backend의 leader election, lock lease, ownership 확인을 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @property db Exposed database backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property options Exposed database backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property historyRecorder Exposed database backend 호출과 상태 계산에 사용하는 속성입니다.
 */
class ExposedJdbcLeaderElector private constructor(
    private val db: Database,
    val options: ExposedJdbcLeaderElectionOptions,
    private val historyRecorder: SafeLeaderHistoryRecorder? = null,
): LeaderElector,
    LeaderBackendDiagnosticsProvider by ExposedJdbcLeaderBackendDiagnostics,
    io.bluetape4k.leader.LeaderLeaseAcquirerSupport {

    override val leaseAcquirerDelegate: io.bluetape4k.leader.LeaderLeaseAcquirer by lazy {
        io.bluetape4k.leader.internal.LeaderElectorLeaseAdapter({ this }, options.leaderOptions)
    }

    companion object : KLogging() {

        internal const val EXPOSED_JDBC_FACTORY_BEAN_NAME = "exposed-jdbc-leader-elector"
        internal val ERROR_CLASSIFIER = CompositeBackendErrorClassifier(ExposedJdbcBackendErrorClassifier)

        /**
         * `invoke` 호출은 Exposed database backend leader election 계약의 일부 동작을 수행합니다.
         *
         * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
         */
        @JvmStatic
        @JvmOverloads
        operator fun invoke(
            db: Database,
            options: ExposedJdbcLeaderElectionOptions = ExposedJdbcLeaderElectionOptions.Default,
            historyRecorder: SafeLeaderHistoryRecorder? = null,
        ): ExposedJdbcLeaderElector {
            ExposedJdbcSchemaInitializer.ensureSchema(db)
            return ExposedJdbcLeaderElector(db, options, historyRecorder)
        }
    }

    /**
     * `선언` 호출은 Exposed database backend leader election 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
     */
    override fun <T> runIfLeader(lockName: String, action: () -> T): T? {
        validateExposedLockName(lockName)

        val lock = ExposedJdbcLock(
            db = db,
            lockName = lockName,
            retryStrategy = options.retryStrategy,
            lockOwner = options.lockOwner,
            useDbTime = options.leaderOptions.useDbTime,
        )
        log.debug { "리더 승격을 요청합니다. lockName=$lockName" }

        if (!lock.tryLock(options.leaderOptions.waitTime, options.leaderOptions.leaseTime)) {
            log.debug { "리더 승격 실패 (락 획득 불가). lockName=$lockName" }
            return null
        }

        log.debug { "리더로 승격하여 작업을 수행합니다. lockName=$lockName" }

        val startedAt = Instant.now()
        val acquiredAtNanos = System.nanoTime()

        val delegate = ExposedJdbcLockExtendDelegate(lock)
        val identity = LockIdentity(
            lockName = lockName,
            kind = LockIdentity.AnnotationKind.SINGLE,
            factoryBeanName = EXPOSED_JDBC_FACTORY_BEAN_NAME,
        )
        val handle = LeaderLockHandle.real(
            identity = identity,
            token = lock.token,
            acquiredAtNanos = acquiredAtNanos,
            extendDelegate = delegate,
        )
        val watchdog = LeaderLeaseAutoExtender.start(
            options.leaderOptions.autoExtend,
            options.leaderOptions.leaseTime,
            delegate,
            ERROR_CLASSIFIER,
        )

        val record = historyRecorder?.let {
            LeaderLockHistoryRecord(
                lockName = lockName,
                token = lock.token,
                kind = LockIdentity.AnnotationKind.SINGLE,
                acquiredAt = startedAt,
                lockedUntil = startedAt.plusMillis(options.leaderOptions.leaseTime.inWholeMilliseconds),
                nodeId = options.lockOwner,
            )
        }
        val key = record?.let { historyRecorder.recordAcquired(it) }
        val effectiveKey: LeaderHistoryKey? =
            key ?: record?.let { LeaderHistoryKey(lockName = lockName, token = lock.token) }

        try {
            return try {
                val result = AopScopeAccess.withPushedSync(handle) { action() }
                val finishedAt = Instant.now()
                val durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - acquiredAtNanos)
                effectiveKey?.let { historyRecorder?.recordCompleted(it, finishedAt, durationMs) }
                result
            } catch (e: CancellationException) {
                throw e
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw e
            } catch (e: Exception) {
                val finishedAt = Instant.now()
                val durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - acquiredAtNanos)
                effectiveKey?.let { historyRecorder?.recordFailed(it, finishedAt, durationMs, e) }
                throw e
            }
        } finally {
            watchdog.close()
            try {
                lock.unlock(options.leaderOptions.minLeaseTime, acquiredAtNanos)
                log.debug { "리더 권한을 반납했습니다. lockName=$lockName" }
            } catch (e: Exception) {
                log.warn(e) { "락 해제 실패. lockName=$lockName" }
            }
        }
    }

    /**
     * `선언` 호출은 Exposed database backend leader election 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
     */
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    override fun <T> runAsyncIfLeader(
        lockName: String,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> {
        validateExposedLockName(lockName)

        val lock = ExposedJdbcLock(
            db = db,
            lockName = lockName,
            retryStrategy = options.retryStrategy,
            lockOwner = options.lockOwner,
            useDbTime = options.leaderOptions.useDbTime,
        )

        val resultFuture = CompletableFuture<T?>()
        val actionFutureRef = AtomicReference<CompletableFuture<*>?>()
        val pipelineFuture = CompletableFuture
            .supplyAsync(
                {
                    lock.tryLock(options.leaderOptions.waitTime, options.leaderOptions.leaseTime) {
                        resultFuture.isCancelled
                    }
                },
                executor,
            )
            .thenComposeAsync({ acquired ->
                if (!acquired) {
                    log.debug { "리더 승격 실패 (비동기). lockName=$lockName" }
                    CompletableFuture.completedFuture(null)
                } else {
                    if (resultFuture.isCancelled) {
                        lock.unlock()
                        return@thenComposeAsync CompletableFuture.failedFuture<T?>(
                            java.util.concurrent.CancellationException("runAsyncIfLeader result was cancelled"),
                        )
                    }

                    log.debug { "리더로 승격하여 비동기 작업을 수행합니다. lockName=$lockName" }
                    val startedAt = Instant.now()
                    val acquiredAtNanos = System.nanoTime()
                    val delegate = ExposedJdbcLockExtendDelegate(lock)
                    val watchdog = LeaderLeaseAutoExtender.start(
                        options.leaderOptions.autoExtend,
                        options.leaderOptions.leaseTime,
                        delegate,
                        ERROR_CLASSIFIER,
                    )

                    val record = historyRecorder?.let {
                        LeaderLockHistoryRecord(
                            lockName = lockName,
                            token = lock.token,
                            kind = LockIdentity.AnnotationKind.SINGLE,
                            acquiredAt = startedAt,
                            lockedUntil = startedAt.plusMillis(options.leaderOptions.leaseTime.inWholeMilliseconds),
                            nodeId = options.lockOwner,
                        )
                    }
                    val key = record?.let { historyRecorder.recordAcquired(it) }
                    val effectiveKey: LeaderHistoryKey? =
                        key ?: record?.let { LeaderHistoryKey(lockName = lockName, token = lock.token) }

                    val terminal = AtomicBoolean()
                    val finishAction: (Throwable?) -> Unit = { throwable ->
                        if (terminal.compareAndSet(false, true)) {
                            watchdog.close()
                            val finishedAt = Instant.now()
                            val durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - acquiredAtNanos)
                            when {
                                throwable == null -> effectiveKey?.let {
                                    historyRecorder?.recordCompleted(it, finishedAt, durationMs)
                                }
                                else -> effectiveKey?.let {
                                    historyRecorder?.recordFailed(it, finishedAt, durationMs, throwable)
                                }
                            }
                            runCatching { lock.unlock(options.leaderOptions.minLeaseTime, acquiredAtNanos) }
                                .onSuccess { log.debug { "비동기 리더 권한을 반납했습니다. lockName=$lockName" } }
                                .onFailure { e -> log.warn(e) { "비동기 락 해제 실패. lockName=$lockName" } }
                        }
                    }

                    if (resultFuture.isCancelled) {
                        val cancellation = java.util.concurrent.CancellationException(
                            "runAsyncIfLeader result was cancelled before action",
                        )
                        finishAction(cancellation)
                        return@thenComposeAsync CompletableFuture.failedFuture(cancellation)
                    }

                    val actionFuture = runCatching { action() }
                        .getOrElse { e ->
                            finishAction(e)
                            return@thenComposeAsync CompletableFuture.failedFuture(e)
                        }

                    val terminalFuture = actionFuture.whenComplete { _, throwable ->
                        finishAction(throwable)
                    }
                    actionFutureRef.set(actionFuture)
                    if (resultFuture.isCancelled) actionFuture.cancel(false)
                    terminalFuture
                }
            }, executor)

        resultFuture.whenComplete { _, _ ->
            if (resultFuture.isCancelled) actionFutureRef.get()?.cancel(false)
        }
        pipelineFuture.whenComplete { value, throwable ->
            if (throwable == null) {
                resultFuture.complete(value)
            } else {
                resultFuture.completeExceptionally(throwable)
            }
        }
        return resultFuture
    }
}
