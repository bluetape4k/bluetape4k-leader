package io.bluetape4k.leader.exposed.jdbc

import io.bluetape4k.concurrent.completableFutureOf
import io.bluetape4k.concurrent.failedCompletableFutureOf
import io.bluetape4k.concurrent.futureOf
import io.bluetape4k.leader.AopScopeAccess
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderLeaseAutoExtender
import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider
import io.bluetape4k.leader.exposed.jdbc.internal.ExposedJdbcBackendErrorClassifier
import io.bluetape4k.leader.exposed.jdbc.internal.ExposedJdbcLockExtendDelegate
import io.bluetape4k.leader.exposed.jdbc.internal.unwrapCompletionCause
import io.bluetape4k.leader.exposed.jdbc.internal.validateExposedLockName
import io.bluetape4k.leader.exposed.jdbc.lock.ExposedJdbcLock
import io.bluetape4k.leader.exposed.jdbc.lock.ExposedJdbcSchemaInitializer
import io.bluetape4k.leader.history.LeaderHistoryKey
import io.bluetape4k.leader.history.LeaderLockHistoryRecord
import io.bluetape4k.leader.history.SafeLeaderHistoryRecorder
import io.bluetape4k.leader.internal.CompositeBackendErrorClassifier
import io.bluetape4k.leader.internal.LeaderElectorLeaseAdapter
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
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * `ExposedJdbcLeaderElector`는 JDBC lock 행을 이용해 단일 leader의 blocking/async 실행과 lease 수명을 관리합니다.
 *
 * @property db 락 테이블을 조회·변경하고 트랜잭션을 실행할 Exposed JDBC 데이터베이스입니다.
 * @property options 대기 시간, lease/minimum lease 시간, 재시도, owner, DB 시간 사용 여부를 담은 전역 선거 설정입니다.
 * @property historyRecorder null이면 이 elector가 획득·완료·실패 이력을 기록하지 않고, 값이 있으면 안전한 history recorder로 기록합니다.
 */
class ExposedJdbcLeaderElector private constructor(
    private val db: Database,
    val options: ExposedJdbcLeaderElectionOptions,
    private val historyRecorder: SafeLeaderHistoryRecorder? = null,
): LeaderElector,
   LeaderBackendDiagnosticsProvider by ExposedJdbcLeaderBackendDiagnostics,
   io.bluetape4k.leader.LeaderLeaseAcquirerSupport {

    override val leaseAcquirerDelegate: io.bluetape4k.leader.LeaderLeaseAcquirer by lazy {
        LeaderElectorLeaseAdapter({ this }, options.leaderOptions)
    }

    companion object: KLogging() {

        internal const val EXPOSED_JDBC_FACTORY_BEAN_NAME = "exposed-jdbc-leader-elector"
        internal val ERROR_CLASSIFIER = CompositeBackendErrorClassifier(ExposedJdbcBackendErrorClassifier)

        /**
         * 데이터베이스 스키마를 한 번 보장한 뒤 JDBC elector를 생성합니다.
         * [options]를 생략하면 [ExposedJdbcLeaderElectionOptions.Default]를 사용하고,
         * [historyRecorder]가 null이면 영속 history 기록을 끕니다.
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
     * [lockName]의 JDBC 락을 [ExposedJdbcLeaderElectionOptions.leaderOptions.waitTime] 동안 시도합니다.
     * 획득하지 못하면 예외 없이 null을 반환하고, 획득하면 [action]을 실행한 뒤 lease를 반납합니다.
     * action의 반환값은 그대로 전달하며 action 예외는 호출자에게 전파됩니다.
     */
    override fun <T> runIfLeader(lockName: String, action: () -> T): T? {
        lockName.validateExposedLockName()

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
     * [lockName]의 JDBC 락 획득과 [action] 실행을 [executor]에 비동기로 위임합니다.
     * 경합으로 획득하지 못한 경우 완료된 future의 값은 null이며, action future의 성공·실패와
     * lease 해제 결과를 최종 future에 반영합니다. 반환 future가 취소되면 대기 또는 action을 취소하고
     * 이미 획득한 락을 정리합니다.
     */
    @Suppress("CyclomaticComplexMethod", "LongMethod", "TooGenericExceptionCaught")
    override fun <T> runAsyncIfLeader(
        lockName: String,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> {
        lockName.validateExposedLockName()

        val lock = ExposedJdbcLock(
            db = db,
            lockName = lockName,
            retryStrategy = options.retryStrategy,
            lockOwner = options.lockOwner,
            useDbTime = options.leaderOptions.useDbTime,
        )

        val resultFuture = CompletableFuture<T?>()
        val actionFutureRef = AtomicReference<CompletableFuture<*>?>()
        val lockAcquired = AtomicBoolean()
        val acquiredAtNanosRef = AtomicLong()
        val lifecycleStarted = AtomicBoolean()
        val rejectionCleanupClaimed = AtomicBoolean()
        val releaseIfUnclaimed: () -> Unit = {
            val canUnlock = lockAcquired.get() &&
                    !lifecycleStarted.get() &&
                    rejectionCleanupClaimed.compareAndSet(false, true)

            if (canUnlock) {
                runCatching {
                    lock.unlock(options.leaderOptions.minLeaseTime, acquiredAtNanosRef.get())
                }.onSuccess {
                    log.debug { "executor 거부 후 비동기 락을 반납했습니다. lockName=$lockName" }
                }.onFailure { e ->
                    log.warn(e) { "executor 거부 후 비동기 락 해제 실패. lockName=$lockName" }
                }
            }
        }
        val acquisitionFuture = futureOf(executor) {
            lock.tryLock(options.leaderOptions.waitTime, options.leaderOptions.leaseTime) {
                resultFuture.isCancelled
            }.also { acquired ->
                if (acquired) {
                    acquiredAtNanosRef.set(System.nanoTime())
                    lockAcquired.set(true)
                }
            }
        }

        val pipelineFuture: CompletableFuture<T?> = try {
            acquisitionFuture.thenComposeAsync({ acquired ->
                if (!acquired) {
                    log.debug { "리더 승격 실패 (비동기). lockName=$lockName" }
                    completableFutureOf(null)
                } else {
                    lifecycleStarted.set(true)
                    val startedAt = Instant.now()
                    val acquiredAtNanos = System.nanoTime()
                    val terminal = AtomicBoolean()
                    var watchdog: AutoCloseable = AutoCloseable { }
                    var effectiveKey: LeaderHistoryKey? = null
                    val finishAction: (Throwable?) -> CompletableFuture<Throwable?> = { throwable ->
                        if (!terminal.compareAndSet(false, true)) {
                            completableFutureOf(null)
                        } else {
                            LeaderLeaseAutoExtender.closeAsync(watchdog).handle { _, closeFailure ->
                                var cleanupFailure = closeFailure?.unwrapCompletionCause()?.also { e ->
                                    log.warn(e) { "비동기 watchdog 종료 실패. lockName=$lockName" }
                                }

                                runCatching {
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
                                }.onFailure { e ->
                                    cleanupFailure = cleanupFailure?.also { it.addSuppressed(e) } ?: e
                                    log.warn(e) { "비동기 history 기록 실패. lockName=$lockName" }
                                }

                                runCatching {
                                    lock.unlock(options.leaderOptions.minLeaseTime, acquiredAtNanos)
                                }.onSuccess {
                                    log.debug { "비동기 리더 권한을 반납했습니다. lockName=$lockName" }
                                }.onFailure { e ->
                                    cleanupFailure = cleanupFailure?.also { it.addSuppressed(e) } ?: e
                                    log.warn(e) { "비동기 락 해제 실패. lockName=$lockName" }
                                }

                                if (throwable != null) {
                                    cleanupFailure?.let { throwable.addSuppressed(it) }
                                    null
                                } else {
                                    cleanupFailure
                                }
                            }
                        }
                    }

                    if (resultFuture.isCancelled) {
                        val cancellation = CancellationException("runAsyncIfLeader result was cancelled")
                        return@thenComposeAsync finishAction(cancellation).thenCompose {
                            failedCompletableFutureOf(cancellation)
                        }
                    }

                    log.debug { "리더로 승격하여 비동기 작업을 수행합니다. lockName=$lockName" }
                    val delegate = ExposedJdbcLockExtendDelegate(lock)

                    try {
                        watchdog = LeaderLeaseAutoExtender.start(
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
                        val fallbackKey = record?.let { LeaderHistoryKey(lockName = lockName, token = lock.token) }
                        effectiveKey = fallbackKey
                        val key = record?.let { historyRecorder.recordAcquired(it) }
                        effectiveKey = key ?: fallbackKey
                    } catch (e: Throwable) {
                        return@thenComposeAsync finishAction(e).thenCompose {
                            failedCompletableFutureOf(e)
                        }
                    }

                    if (resultFuture.isCancelled) {
                        val cancellation = java.util.concurrent.CancellationException(
                            "runAsyncIfLeader result was cancelled before action",
                        )
                        return@thenComposeAsync finishAction(cancellation).thenCompose {
                            failedCompletableFutureOf(cancellation)
                        }
                    }

                    val actionFuture = runCatching { action() }
                        .getOrElse { e ->
                            return@thenComposeAsync finishAction(e).thenCompose {
                                failedCompletableFutureOf(e)
                            }
                        }

                    val terminalFuture = actionFuture.handle { value, throwable ->
                        Triple(value, throwable, finishAction(throwable))
                    }.thenCompose { (value, throwable, cleanup) ->
                        cleanup.thenCompose { cleanupFailure ->
                            if (throwable != null) {
                                failedCompletableFutureOf<T?>(throwable)
                            } else if (cleanupFailure != null) {
                                failedCompletableFutureOf(cleanupFailure)
                            } else {
                                completableFutureOf(value)
                            }
                        }
                    }
                    actionFutureRef.set(actionFuture)
                    if (resultFuture.isCancelled) actionFuture.cancel(false)
                    terminalFuture
                }
            }, executor)
        } catch (e: Throwable) {
            acquisitionFuture.whenComplete { acquired, _ ->
                if (acquired == true) releaseIfUnclaimed()
            }
            failedCompletableFutureOf(e)
        }

        resultFuture.whenComplete { _, _ ->
            if (resultFuture.isCancelled) actionFutureRef.get()?.cancel(false)
        }

        pipelineFuture.whenComplete { value, throwable ->
            if (throwable != null) releaseIfUnclaimed()
            if (throwable == null) {
                resultFuture.complete(value)
            } else {
                resultFuture.completeExceptionally(throwable)
            }
        }
        return resultFuture
    }
}
