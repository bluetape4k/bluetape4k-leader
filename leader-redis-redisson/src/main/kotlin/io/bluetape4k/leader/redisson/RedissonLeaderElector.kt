package io.bluetape4k.leader.redisson

import io.bluetape4k.concurrent.completableFutureOf
import io.bluetape4k.concurrent.failedCompletableFutureOf
import io.bluetape4k.leader.AopScopeAccess
import io.bluetape4k.leader.AsyncLifecycle
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderLeaseAcquirer
import io.bluetape4k.leader.LeaderLeaseAcquirerSupport
import io.bluetape4k.leader.LeaderLeaseAutoExtender
import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.asCompletionException
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider
import io.bluetape4k.leader.internal.CompositeBackendErrorClassifier
import io.bluetape4k.leader.internal.LeaderElectorLeaseAdapter
import io.bluetape4k.leader.internal.LeaderFutureBridge
import io.bluetape4k.leader.redisson.internal.RedissonBackendErrorClassifier
import io.bluetape4k.leader.redisson.internal.RedissonLockExtendDelegate
import io.bluetape4k.leader.remainingMinLeaseTime
import io.bluetape4k.leader.toActionFailedResult
import io.bluetape4k.leader.unwrapCompletionException
import io.bluetape4k.leader.validateLockName
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.error
import org.redisson.api.RLock
import org.redisson.api.RedissonClient
import org.redisson.client.RedisException
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.toJavaDuration
import io.bluetape4k.leader.redisson.runAsyncIfLeader as currentRunAsyncIfLeader
import io.bluetape4k.leader.redisson.runIfLeader as currentRunIfLeader

/**
 * `RedissonLeaderElector`는 Redis Redisson의 blocking 단일 선출을 제공하며 lockName의 lease 획득·연장·해제를 관리합니다.
 *
 * lock 또는 slot을 획득하지 못한 `runIfLeader` 호출은 예외 대신 `null`/skip 결과를 반환합니다.
 * @property redissonClient `lockName`별 `RLock`을 만들고 lease 연산을 실행하는 Redisson client입니다.
 * @property options lock 대기·lease·최소 lease·auto-extend 동작을 정하는 단일 선출 정책입니다.
 */
// Single elector는 sync/async, lease lifecycle, diagnostics 계약을 함께 구현합니다.
@Suppress("TooManyFunctions")
class RedissonLeaderElector private constructor(
    private val redissonClient: RedissonClient,
    private val options: LeaderElectionOptions,
): LeaderElector,
   LeaderBackendDiagnosticsProvider by RedissonLeaderBackendDiagnostics(redissonClient),
   LeaderLeaseAcquirerSupport {

    companion object: KLogging() {
        internal const val REDISSON_FACTORY_BEAN_NAME = "redisson-leader-elector"
        internal val ERROR_CLASSIFIER = CompositeBackendErrorClassifier(RedissonBackendErrorClassifier)

        @JvmStatic
        operator fun invoke(
            redissonClient: RedissonClient,
            options: LeaderElectionOptions = LeaderElectionOptions.Default,
        ): RedissonLeaderElector {
            return RedissonLeaderElector(redissonClient, options)
        }
    }

    override val leaseAcquirerDelegate: LeaderLeaseAcquirer by lazy {
        LeaderElectorLeaseAdapter({ this }, options)
    }

    private val waitTimeMills = options.waitTime.inWholeMilliseconds
    private val leaseTimeMills = options.leaseTime.inWholeMilliseconds

    override fun <T> runIfLeader(lockName: String, action: () -> T): T? =
        runImpl(lockName, auditLeaderId = null, action)

    override fun <T> runIfLeader(slot: LeaderSlot, action: () -> T): T? =
        runImpl(slot.lockName, auditLeaderId = slot.leaderId, action)

    override fun <T> runIfLeaderResult(slot: LeaderSlot, action: () -> T): LeaderRunResult<T> {
        var elected = false
        val value = try {
            runImpl(slot.lockName, auditLeaderId = slot.leaderId) {
                elected = true
                action()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        } catch (e: Exception) {
            if (elected) {
                return LeaderRunResult.ActionFailed(e)
            }
            throw e
        }
        return if (elected) LeaderRunResult.Elected(value, leaderId = slot.leaderId) else LeaderRunResult.Skipped
    }

    private fun <T> runImpl(lockName: String, auditLeaderId: String?, action: () -> T): T? {
        lockName.validateLockName()

        val lock: RLock = redissonClient.getLock(lockName)

        log.debug { "Leader 승격을 요청합니다 ..." }

        try {
            // T8: autoExtend 여부와 무관하게 항상 명시적 leaseTime 사용 — Redisson 내장 watchdog 비활성화.
            val acquired = try {
                lock.tryLock(waitTimeMills, leaseTimeMills, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw e
            } catch (e: RedisException) {
                e.rethrowInterruptedCause()
            }
            if (!acquired) {
                log.debug { "Leader 승격 실패 (슬롯 없음). lock=$lockName" }
                return null
            }
            val acquiredAtNanos = System.nanoTime()
            val acquiringThreadId = Thread.currentThread().threadId()
            val delegate = RedissonLockExtendDelegate(redissonClient, lock, acquiringThreadId)
            val identity = LockIdentity(
                lockName = lockName,
                kind = LockIdentity.AnnotationKind.SINGLE,
                factoryBeanName = REDISSON_FACTORY_BEAN_NAME,
            )
            val handle = LeaderLockHandle.real(
                identity = identity,
                token = lockName,
                acquiredAtNanos = acquiredAtNanos,
                acquiringThreadId = acquiringThreadId,
                extendDelegate = delegate,
                auditLeaderId = auditLeaderId,
            )
            val watchdog = LeaderLeaseAutoExtender.start(
                options.autoExtend,
                options.leaseTime,
                delegate,
                ERROR_CLASSIFIER,
            )
            log.debug { "Leader로 승격하여 작업을 수행합니다. lock=$lockName" }
            try {
                return AopScopeAccess.withPushedSync(handle) { action() }
            } finally {
                watchdog.close()
                if (lock.isHeldByThread(acquiringThreadId)) {
                    runCatching {
                        releaseLock(lock, acquiredAtNanos)
                        log.debug { "작업이 완료되어 Leader 권한을 반납했습니다. lock=$lockName" }
                    }
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            log.error(e) { "Fail to run as leader" }
            throw e
        }
    }

    private fun Throwable.rethrowInterruptedCause(): Nothing {
        var current: Throwable? = this
        while (current != null) {
            if (current is InterruptedException) {
                Thread.currentThread().interrupt()
                throw current
            }
            current = current.cause
        }
        throw this
    }

    override fun <T> runAsyncIfLeader(
        lockName: String,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> =
        runAsyncImpl(lockName, auditLeaderId = null, executor, action)

    override fun <T> runAsyncIfLeader(
        slot: LeaderSlot,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> =
        runAsyncImpl(slot.lockName, auditLeaderId = slot.leaderId, executor, action)

    override fun <T> runAsyncIfLeaderResult(
        slot: LeaderSlot,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<LeaderRunResult<T>> {
        val elected = AtomicBoolean(false)
        val cancellationRelay = LeaderFutureBridge.cancellationRelay()
        return LeaderFutureBridge.map(runAsyncIfLeader(slot, executor) {
            elected.set(true)
            cancellationRelay.invoke(action)
        }, cancellationRelay) { value, failure ->
            when {
                failure != null && elected.get() -> failure.toActionFailedResult()
                failure != null -> throw failure.asCompletionException()
                elected.get()   -> LeaderRunResult.Elected(value, leaderId = slot.leaderId)
                else            -> LeaderRunResult.Skipped
            }
        }
    }

    @Suppress("LongMethod", "TooGenericExceptionCaught")
    private inline fun <T> runAsyncImpl(
        lockName: String,
        auditLeaderId: String?,
        executor: Executor,
        crossinline action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> {
        lockName.validateLockName()

        val lock: RLock = redissonClient.getLock(lockName)

        try {
            val currentThreadId = Thread.currentThread().threadId()
            val rejectionCleanup = AsyncLockRejectionCleanup(lock, currentThreadId)
            val cancellationRelay = LeaderFutureBridge.cancellationRelay()
            log.debug { "Leader 승격을 요청합니다 ... lock=$lockName, currentThreadId=$currentThreadId" }

            // T8: 항상 명시적 leaseTime — Redisson 내장 watchdog 비활성화.
            val acquisitionFuture = lock
                .tryLockAsync(waitTimeMills, leaseTimeMills, TimeUnit.MILLISECONDS, currentThreadId)
                .toCompletableFuture()
                .thenApply { acquired ->
                    if (acquired) rejectionCleanup.markAcquired()
                    acquired
                }

            val pipelineFuture = acquisitionFuture
                .thenComposeAsync(
                    { acquired ->
                        if (acquired) {
                            if (!rejectionCleanup.markLifecycleStarted()) {
                                failedCompletableFutureOf(
                                    CancellationException("leader action was cancelled before start"),
                                )
                            } else try {
                                executeActionAsync(
                                    lock,
                                    auditLeaderId,
                                    currentThreadId,
                                    rejectionCleanup.acquiredAtNanos,
                                    cancellationRelay,
                                    action,
                                )
                            } catch (error: Throwable) {
                                releaseAcquiredLockAsync(lock, currentThreadId, rejectionCleanup.acquiredAtNanos)
                                    .handle { _, releaseError ->
                                        if (releaseError != null) {
                                            error.addSuppressed(releaseError.unwrapCompletionException())
                                        }
                                    }
                                    .thenCompose { failedCompletableFutureOf(error) }
                            }
                        } else {
                            log.debug { "Leader 승격 실패 (슬롯 없음). lock=$lockName" }
                            completableFutureOf(null)
                        }
                    },
                    executor
                )

            acquisitionFuture.whenComplete { acquired, _ ->
                if (acquired == true && pipelineFuture.isCancelled) {
                    rejectionCleanup.release<Any?>(
                        CancellationException("leader result future was cancelled before action"),
                    )
                }
            }
            return LeaderFutureBridge.flatMap(pipelineFuture, cancellationRelay) { value, failure ->
                if (failure != null) {
                    rejectionCleanup.release(failure.unwrapCompletionException()!!)
                } else {
                    completableFutureOf(value)
                }
            }

        } catch (e: Throwable) {
            log.error(e) { "Fail to runAsync as Leader" }
            return failedCompletableFutureOf(e)
        }
    }

    private inner class AsyncLockRejectionCleanup(
        private val lock: RLock,
        private val currentThreadId: Long,
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

        fun <T> release(failure: Throwable): CompletableFuture<T?> {
            if (!acquired.get() || !lifecycle.compareAndSet(AsyncLifecycle.WAITING, AsyncLifecycle.CLEANUP)) {
                return failedCompletableFutureOf(failure)
            }
            return releaseAcquiredLockAsync(lock, currentThreadId, acquiredAtNanos)
                .exceptionally { releaseError ->
                    log.error(releaseError) {
                        "Fail to release lock after executor rejection. lock=${lock.name}, threadId=$currentThreadId"
                    }
                }
                .thenCompose { failedCompletableFutureOf(failure) }
        }
    }

    /** 비동기 lock 획득 후 lease watchdog과 action future를 정리하고, 경합이면 완료값 `null`을 반환합니다. */
    private inline fun <T> executeActionAsync(
        lock: RLock,
        auditLeaderId: String?,
        currentThreadId: Long,
        acquiredAtNanos: Long,
        cancellationRelay: LeaderFutureBridge.CancellationRelay,
        crossinline action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> {
        val lockName = lock.name
        val delegate = RedissonLockExtendDelegate(redissonClient, lock, currentThreadId)
        val identity = LockIdentity(
            lockName = lockName,
            kind = LockIdentity.AnnotationKind.SINGLE,
            factoryBeanName = REDISSON_FACTORY_BEAN_NAME,
        )
        val handle = LeaderLockHandle.real(
            identity = identity,
            token = lockName,
            acquiredAtNanos = acquiredAtNanos,
            acquiringThreadId = currentThreadId,
            extendDelegate = delegate,
            auditLeaderId = auditLeaderId,
        )
        val watchdog = LeaderLeaseAutoExtender.start(
            options.autoExtend,
            options.leaseTime,
            delegate,
            ERROR_CLASSIFIER,
        )
        log.debug { "Leader로 승격하여 비동기 작업을 수행합니다. lock=$lockName, threadId=$currentThreadId" }

        val actionFuture = runCatching {
            cancellationRelay.invoke { AopScopeAccess.withPushedSync(handle) { action() } }
        }.getOrElse { error ->
            return releaseAndPropagate(lock, currentThreadId, acquiredAtNanos, watchdog, error, null)
        }

        return actionFuture
            .handle<Pair<T?, Throwable?>> { value, error -> Pair(value, error) }
            .thenCompose { (value, error) ->
                releaseAndPropagate(lock, currentThreadId, acquiredAtNanos, watchdog, error, value)
            }
    }

    private fun releaseLock(lock: RLock, acquiredAtNanos: Long) {
        val remaining = remainingMinLeaseTime(acquiredAtNanos, options.minLeaseTime)
        if (remaining > Duration.ZERO) {
            redissonClient.keys.expire(remaining.toJavaDuration(), lock.name)
        } else {
            lock.unlock()
        }
    }

    private fun <T> releaseAndPropagate(
        lock: RLock,
        currentThreadId: Long,
        acquiredAtNanos: Long,
        watchdog: AutoCloseable,
        error: Throwable?,
        value: T?,
    ): CompletableFuture<T?> {
        return LeaderLeaseAutoExtender.closeAsync(watchdog)
            .handle { _, closeFailure ->
                val closeCause = closeFailure?.unwrapCompletionException()
                if (closeCause != null && error != null) error.addSuppressed(closeCause)
                if (error == null) closeCause else null
            }
            .thenCompose { closeFailure ->
                releaseLockAsync(lock, currentThreadId, acquiredAtNanos)
                    .exceptionally { releaseError ->
                        log.error(releaseError) {
                            "Fail to release lock. lock=${lock.name}, threadId=$currentThreadId"
                        }
                    }
                    .thenCompose {
                        if (error != null) {
                            failedCompletableFutureOf(error)
                        } else if (closeFailure != null) {
                            failedCompletableFutureOf(closeFailure)
                        } else {
                            completableFutureOf(value)
                        }
                    }
            }
    }

    private fun releaseLockAsync(lock: RLock, currentThreadId: Long, acquiredAtNanos: Long): CompletableFuture<Unit> {
        return try {
            lock.isHeldByThreadAsync(currentThreadId).toCompletableFuture().thenCompose { held ->
                if (held) {
                    releaseAcquiredLockAsync(lock, currentThreadId, acquiredAtNanos)
                } else {
                    completableFutureOf(Unit)
                }
            }
        } catch (e: Throwable) {
            failedCompletableFutureOf(e)
        }
    }

    private fun releaseAcquiredLockAsync(
        lock: RLock,
        currentThreadId: Long,
        acquiredAtNanos: Long,
    ): CompletableFuture<Unit> {
        val lockName = lock.name
        return try {
            val remaining = remainingMinLeaseTime(acquiredAtNanos, options.minLeaseTime)
            val releaseFuture: CompletableFuture<*> = if (remaining > Duration.ZERO) {
                redissonClient.keys.expireAsync(remaining.toJavaDuration(), lockName).toCompletableFuture()
            } else {
                lock.unlockAsync(currentThreadId).toCompletableFuture()
            }
            releaseFuture.thenApply {
                log.debug { "Leader 권한을 반납했습니다. lock=$lockName, threadId=$currentThreadId" }
            }
        } catch (e: Throwable) {
            failedCompletableFutureOf(e)
        }
    }
}

/** 리팩터링 전 JVM facade의 바이너리 호환성을 보존하는 shim입니다. */
@Deprecated("리팩터링 전 JVM facade 호환성 유지용", level = DeprecationLevel.HIDDEN)
@JvmName("runIfLeader")
inline fun <T> RedissonClient.legacyRunIfLeader(
    jobName: String,
    options: LeaderElectionOptions = LeaderElectionOptions.Default,
    crossinline action: () -> T,
): T? = this.currentRunIfLeader(jobName, options, action)

/** Binary compatibility shim for the pre-refactor JVM facade. */
@Deprecated("Binary compatibility shim", level = DeprecationLevel.HIDDEN)
@JvmName("runAsyncIfLeader")
inline fun <T> RedissonClient.legacyRunAsyncIfLeader(
    jobName: String,
    executor: Executor = java.util.concurrent.ForkJoinPool.commonPool(),
    options: LeaderElectionOptions = LeaderElectionOptions.Default,
    crossinline action: () -> CompletableFuture<T>,
): CompletableFuture<T?> = this.currentRunAsyncIfLeader(jobName, executor, options, action)
