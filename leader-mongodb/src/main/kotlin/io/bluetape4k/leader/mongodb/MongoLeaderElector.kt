package io.bluetape4k.leader.mongodb

import com.mongodb.client.MongoCollection
import io.bluetape4k.concurrent.virtualthread.VirtualThreadExecutor
import io.bluetape4k.leader.AopScopeAccess
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderLeaseAutoExtender
import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider
import io.bluetape4k.leader.history.LeaderHistoryKey
import io.bluetape4k.leader.history.LeaderLockHistoryRecord
import io.bluetape4k.leader.history.SafeLeaderHistoryRecorder
import io.bluetape4k.leader.internal.CompositeBackendErrorClassifier
import io.bluetape4k.leader.internal.LeaderFutureBridge
import io.bluetape4k.leader.mongodb.internal.MongoBackendErrorClassifier
import io.bluetape4k.leader.mongodb.internal.MongoLockExtendDelegate
import io.bluetape4k.leader.mongodb.lock.MongoLock
import io.bluetape4k.leader.mongodb.lock.validateMongoLockName
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import org.bson.Document
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * `MongoLeaderElector`는 MongoDB backend의 leader election, lock lease, ownership 확인을 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @property collection MongoDB backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property options MongoDB backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property historyRecorder MongoDB backend 호출과 상태 계산에 사용하는 속성입니다.
 */
class MongoLeaderElector private constructor(
    private val collection: MongoCollection<Document>,
    val options: MongoLeaderElectionOptions,
    private val historyRecorder: SafeLeaderHistoryRecorder? = null,
): LeaderElector,
    LeaderBackendDiagnosticsProvider by MongoLeaderBackendDiagnostics,
    io.bluetape4k.leader.LeaderLeaseAcquirerSupport {

    override val leaseAcquirerDelegate: io.bluetape4k.leader.LeaderLeaseAcquirer by lazy {
        io.bluetape4k.leader.internal.LeaderElectorLeaseAdapter({ this }, options.leaderOptions)
    }

    companion object : KLogging() {
        internal const val MONGO_FACTORY_BEAN_NAME = "mongo-leader-elector"
        internal val ERROR_CLASSIFIER = CompositeBackendErrorClassifier(MongoBackendErrorClassifier)

        @JvmStatic
        @JvmOverloads
        operator fun invoke(
            collection: MongoCollection<Document>,
            options: MongoLeaderElectionOptions = MongoLeaderElectionOptions.Default,
            historyRecorder: SafeLeaderHistoryRecorder? = null,
        ): MongoLeaderElector {
            MongoLock.ensureIndexes(collection)
            return MongoLeaderElector(collection, options, historyRecorder)
        }
    }

    override fun <T> runIfLeader(lockName: String, action: () -> T): T? {
        validateMongoLockName(lockName)
        val lock = MongoLock(collection, lockName, options.retryDelay)
        log.debug { "리더 승격을 요청합니다. lockName=$lockName" }

        if (!lock.tryLock(options.leaderOptions.waitTime, options.leaderOptions.leaseTime)) {
            log.debug { "리더 승격 실패 (슬롯 없음). lockName=$lockName" }
            return null
        }

        val startedAt = Instant.now()
        val acquiredAtNanos = System.nanoTime()
        val delegate = MongoLockExtendDelegate(lock)
        val identity = LockIdentity(
            lockName = lockName,
            kind = LockIdentity.AnnotationKind.SINGLE,
            factoryBeanName = MONGO_FACTORY_BEAN_NAME,
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
            )
        }
        val key = record?.let { historyRecorder.recordAcquired(it) }
        val effectiveKey: LeaderHistoryKey? =
            key ?: record?.let { LeaderHistoryKey(lockName = lockName, token = lock.token) }

        log.debug { "리더로 승격하여 작업을 수행합니다. lockName=$lockName" }
        try {
            return try {
                val result = AopScopeAccess.withPushedSync(handle) { action() }
                val finishedAt = Instant.now()
                val durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - acquiredAtNanos)
                effectiveKey?.let { historyRecorder?.recordCompleted(it, finishedAt, durationMs) }
                result
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
            runCatching { lock.unlock(options.leaderOptions.minLeaseTime, acquiredAtNanos) }
                .onSuccess { log.debug { "리더 권한을 반납했습니다. lockName=$lockName" } }
                .onFailure { e -> log.warn(e) { "락 해제 실패. lockName=$lockName" } }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    override fun <T> runAsyncIfLeader(
        lockName: String,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> {
        validateMongoLockName(lockName)
        val lock = MongoLock(collection, lockName, options.retryDelay)

        val rejectionCleanup = AsyncLockRejectionCleanup(lock, lockName)
        val acquisitionFuture = lock
            .tryLockAsync(options.leaderOptions.waitTime, options.leaderOptions.leaseTime)
            .thenApply { acquired ->
                if (acquired) rejectionCleanup.markAcquired()
                acquired
            }
        val pipelineFuture = acquisitionFuture.thenComposeAsync({ acquired ->
            if (!acquired) {
                log.debug { "리더 승격 실패 (슬롯 없음, 비동기). lockName=$lockName" }
                CompletableFuture.completedFuture(null)
            } else {
                if (!rejectionCleanup.markLifecycleStarted()) {
                    CompletableFuture.failedFuture(CancellationException("leader action was cancelled before start"))
                } else try {
                    runAcquiredAsync(lock, lockName, rejectionCleanup.acquiredAtNanos, action)
                } catch (error: Throwable) {
                    releaseAcquiredLock(lock, lockName, rejectionCleanup.acquiredAtNanos, error)
                }
            }
        }, executor)
        acquisitionFuture.whenComplete { acquired, _ ->
            if (acquired == true && pipelineFuture.isCancelled) {
                rejectionCleanup.release<Any?>(
                    CancellationException("leader result future was cancelled before action"),
                )
            }
        }
        return LeaderFutureBridge.flatMap(pipelineFuture) { value, failure ->
            if (failure != null) {
                rejectionCleanup.release(failure.unwrapCompletionCause())
            } else {
                CompletableFuture.completedFuture(value)
            }
        }
    }

    private fun <T> runAcquiredAsync(
        lock: MongoLock,
        lockName: String,
        acquiredAtNanos: Long,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> {
        val startedAt = Instant.now()
        val delegate = MongoLockExtendDelegate(lock)
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
            )
        }
        val key = record?.let { historyRecorder.recordAcquired(it) }
        val effectiveKey = key ?: record?.let { LeaderHistoryKey(lockName = lockName, token = lock.token) }

        log.debug { "리더로 승격하여 비동기 작업을 수행합니다. lockName=$lockName" }
        val actionFuture = runCatching { action() }.getOrElse { error ->
            watchdog.close()
            val finishedAt = Instant.now()
            val durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - acquiredAtNanos)
            effectiveKey?.let { historyRecorder?.recordFailed(it, finishedAt, durationMs, error) }
            return releaseAcquiredLock(lock, lockName, acquiredAtNanos, error)
        }
        return actionFuture.whenComplete { _, failure ->
            watchdog.close()
            val finishedAt = Instant.now()
            val durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - acquiredAtNanos)
            when {
                failure == null -> effectiveKey?.let { historyRecorder?.recordCompleted(it, finishedAt, durationMs) }
                failure is CancellationException -> Unit
                else -> effectiveKey?.let { historyRecorder?.recordFailed(it, finishedAt, durationMs, failure) }
            }
            runCatching { lock.unlock(options.leaderOptions.minLeaseTime, acquiredAtNanos) }
                .onSuccess { log.debug { "비동기 리더 권한을 반납했습니다. lockName=$lockName" } }
                .onFailure { error -> log.warn(error) { "비동기 락 해제 실패. lockName=$lockName" } }
        }.thenApply<T?> { it }
    }

    private fun <T> releaseAcquiredLock(
        lock: MongoLock,
        lockName: String,
        acquiredAtNanos: Long,
        failure: Throwable,
    ): CompletableFuture<T?> {
        runCatching { lock.unlock(options.leaderOptions.minLeaseTime, acquiredAtNanos) }
            .onFailure { error ->
                failure.addSuppressed(error)
                log.warn(error) { "비동기 락 해제 실패. lockName=$lockName" }
            }
        return CompletableFuture.failedFuture(failure)
    }

    private inner class AsyncLockRejectionCleanup(
        private val lock: MongoLock,
        private val lockName: String,
    ) {
        private val acquired = AtomicBoolean()
        private val lifecycle = AtomicReference(AsyncLifecycle.WAITING)
        private val acquiredAtNanosRef = AtomicLong()

        val acquiredAtNanos: Long get() = acquiredAtNanosRef.get()

        fun markAcquired() {
            acquiredAtNanosRef.set(System.nanoTime())
            acquired.set(true)
        }

        fun markLifecycleStarted(): Boolean =
            lifecycle.compareAndSet(AsyncLifecycle.WAITING, AsyncLifecycle.STARTED)

        fun <T> release(failure: Throwable): CompletableFuture<T?> =
            if (acquired.get() && lifecycle.compareAndSet(AsyncLifecycle.WAITING, AsyncLifecycle.CLEANUP)) {
                releaseAcquiredLock(lock, lockName, acquiredAtNanos, failure)
            } else {
                CompletableFuture.failedFuture(failure)
            }
    }

    private fun Throwable.unwrapCompletionCause(): Throwable =
        if (this is CompletionException) cause ?: this else this

    private enum class AsyncLifecycle {
        WAITING,
        STARTED,
        CLEANUP,
    }
}

/**
 * `선언` 호출은 MongoDB backend leader election 계약의 일부 동작을 수행합니다.
 *
 * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
 */
fun <T> MongoCollection<Document>.runIfLeader(
    lockName: String,
    options: MongoLeaderElectionOptions = MongoLeaderElectionOptions.Default,
    action: () -> T,
): T? = MongoLeaderElector(this, options).runIfLeader(lockName, action)

/**
 * `선언` 호출은 MongoDB backend leader election 계약의 일부 동작을 수행합니다.
 *
 * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
 */
fun <T> MongoCollection<Document>.runAsyncIfLeader(
    lockName: String,
    executor: Executor = VirtualThreadExecutor,
    options: MongoLeaderElectionOptions = MongoLeaderElectionOptions.Default,
    action: () -> CompletableFuture<T>,
): CompletableFuture<T?> = MongoLeaderElector(this, options).runAsyncIfLeader(lockName, executor, action)
