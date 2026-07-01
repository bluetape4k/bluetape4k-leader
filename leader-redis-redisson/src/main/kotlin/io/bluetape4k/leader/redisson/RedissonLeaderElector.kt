package io.bluetape4k.leader.redisson

import io.bluetape4k.concurrent.failedCompletableFutureOf
import io.bluetape4k.leader.AopScopeAccess
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderLeaseAutoExtender
import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.internal.CompositeBackendErrorClassifier
import io.bluetape4k.leader.redisson.internal.RedissonBackendErrorClassifier
import io.bluetape4k.leader.redisson.internal.RedissonLockExtendDelegate
import io.bluetape4k.leader.remainingMinLeaseTime
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.error
import io.bluetape4k.support.requireNotBlank
import org.redisson.api.RLock
import org.redisson.api.RedissonClient
import org.redisson.client.RedisException
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Elects a single leader among multiple processes/threads using a Redisson distributed lock.
 *
 * ## Behavior (T8 PR 3)
 * - [runIfLeader]: Acquires the lock synchronously, executes [LeaderElector.runIfLeader], then releases the lock on completion.
 * - [runAsyncIfLeader]: Acquires the lock asynchronously via `tryLockAsync` and releases it via [RLock.unlockAsync] when the `CompletableFuture` completes.
 * - Returns `null` if lock acquisition fails within [LeaderElectionOptions.waitTime] (ShedLock skip-on-contention behavior).
 * - An interrupt during lock wait is wrapped and propagated as [org.redisson.client.RedisException].
 *
 * ## ExtendDelegate Integration
 *
 * - After acquire, creates a [RedissonLockExtendDelegate] shared with [LeaderLockHandle.Real] and the watchdog under the same reference (AC-15).
 * - When the aspect calls `LockExtender.extendActiveLock`, the same delegate executes `RLock.expire(d)`.
 * - Always acquires with an explicit `leaseTime` regardless of autoExtend, disabling Redisson's built-in watchdog.
 *   [LeaderLeaseAutoExtender] acts as the sole watchdog, ensuring R2 watchdog skip semantics.
 *
 * ```kotlin
 * val election = RedissonLeaderElector(redissonClient)
 * val result = election.runIfLeader("my-job") {
 *     processData()
 * }
 * ```
 *
 * @param redissonClient Redisson client
 * @param options Leader election options (waitTime, leaseTime)
 * @see RedissonSuspendLeaderElector Suspend variant for coroutine environments
 */
class RedissonLeaderElector private constructor(
    private val redissonClient: RedissonClient,
    private val options: LeaderElectionOptions,
): LeaderElector {

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
        lockName.requireNotBlank("lockName")

        val lock: RLock = redissonClient.getLock(lockName)

        log.debug { "Leader 승격을 요청합니다 ..." }

        try {
            // T8: autoExtend 여부와 무관하게 항상 명시적 leaseTime 사용 — Redisson 내장 watchdog 비활성화.
            val acquired = lock.tryLock(waitTimeMills, leaseTimeMills, TimeUnit.MILLISECONDS)
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
            throw RedisException("Interrupted while acquiring lock. lock=$lockName", e)
        }
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
        return runAsyncIfLeader(slot, executor) {
            elected.set(true)
            action()
        }.handle { value, failure ->
            when {
                failure != null && elected.get() -> failure.toActionFailedResult()
                failure != null -> throw failure.asCompletionException()
                elected.get() -> LeaderRunResult.Elected(value, leaderId = slot.leaderId)
                else -> LeaderRunResult.Skipped
            }
        }
    }

    private fun <T> runAsyncImpl(
        lockName: String,
        auditLeaderId: String?,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> {
        lockName.requireNotBlank("lockName")

        val lock: RLock = redissonClient.getLock(lockName)

        try {
            val currentThreadId = Thread.currentThread().threadId()
            log.debug { "Leader 승격을 요청합니다 ... lock=$lockName, currentThreadId=$currentThreadId" }

            // T8: 항상 명시적 leaseTime — Redisson 내장 watchdog 비활성화.
            return lock
                .tryLockAsync(waitTimeMills, leaseTimeMills, TimeUnit.MILLISECONDS, currentThreadId)
                .thenComposeAsync({ acquired ->
                    if (acquired) {
                        executeActionAsync(lock, auditLeaderId, currentThreadId, executor, System.nanoTime(), action)
                    } else {
                        log.debug { "Leader 승격 실패 (슬롯 없음). lock=$lockName" }
                        CompletableFuture.completedFuture(null)
                    }
                }, executor)
                .toCompletableFuture()

        } catch (e: Throwable) {
            log.error(e) { "Fail to runAsync as Leader" }
            return failedCompletableFutureOf(e)
        }
    }

    /**
     * Executes the asynchronous [action] while holding the lock and releases the lock on completion (success or failure).
     *
     * Aligned with the Lettuce path by pushing a real leader handle while creating the asynchronous action.
     */
    private fun <T> executeActionAsync(
        lock: RLock,
        auditLeaderId: String?,
        currentThreadId: Long,
        executor: Executor,
        acquiredAtNanos: Long,
        action: () -> CompletableFuture<T>,
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
            AopScopeAccess.withPushedSync(handle) { action() }
        }
            .getOrElse { error ->
                watchdog.close()
                releaseLockAsync(lock, currentThreadId, acquiredAtNanos)
                return failedCompletableFutureOf(error)
            }

        return actionFuture.whenCompleteAsync({ _, _ ->
            watchdog.close()
            releaseLockAsync(lock, currentThreadId, acquiredAtNanos)
        }, executor)
    }

    private fun Throwable.unwrapCompletionCause(): Throwable =
        (this as? CompletionException)?.cause ?: this

    private fun Throwable.toActionFailedResult(): LeaderRunResult.ActionFailed {
        val cause = unwrapCompletionCause()
        if (cause is CancellationException) {
            throw cause
        }
        return LeaderRunResult.ActionFailed(cause)
    }

    private fun Throwable.asCompletionException(): CompletionException =
        this as? CompletionException ?: CompletionException(this)

    private fun releaseLock(lock: RLock, acquiredAtNanos: Long) {
        val remaining = remainingMinLeaseTime(acquiredAtNanos, options.minLeaseTime)
        if (remaining > kotlin.time.Duration.ZERO) {
            redissonClient.keys.expire(remaining.toJavaDuration(), lock.name)
        } else {
            lock.unlock()
        }
    }

    private fun releaseLockAsync(lock: RLock, currentThreadId: Long, acquiredAtNanos: Long) {
        val lockName = lock.name
        if (lock.isHeldByThread(currentThreadId)) {
            val remaining = remainingMinLeaseTime(acquiredAtNanos, options.minLeaseTime)
            val releaseFuture: CompletableFuture<*> = if (remaining > kotlin.time.Duration.ZERO) {
                CompletableFuture.supplyAsync {
                    redissonClient.keys.expire(remaining.toJavaDuration(), lockName)
                }
            } else {
                lock.unlockAsync(currentThreadId).toCompletableFuture()
            }
            releaseFuture
                .whenComplete { _, error ->
                    if (error != null) {
                        log.error(error) { "Fail to release lock. lock=$lockName, threadId=$currentThreadId" }
                    } else {
                        log.debug { "Leader 권한을 반납했습니다. lock=$lockName, threadId=$currentThreadId" }
                    }
                }
        }
    }

    private fun kotlin.time.Duration.toJavaDuration(): java.time.Duration =
        java.time.Duration.ofNanos(inWholeNanoseconds)
}


/**
 * Performs an action via leader election using a Redisson distributed lock.
 */
inline fun <T> RedissonClient.runIfLeader(
    jobName: String,
    options: LeaderElectionOptions = LeaderElectionOptions.Default,
    crossinline action: () -> T,
): T? {
    jobName.requireNotBlank("jobName")
    val leaderElection = RedissonLeaderElector(this, options)
    return leaderElection.runIfLeader(jobName) { action() }
}

/**
 * Performs an asynchronous action via leader election using a Redisson distributed lock.
 */
inline fun <T> RedissonClient.runAsyncIfLeader(
    jobName: String,
    executor: Executor = ForkJoinPool.commonPool(),
    options: LeaderElectionOptions = LeaderElectionOptions.Default,
    crossinline action: () -> CompletableFuture<T>,
): CompletableFuture<T?> {
    jobName.requireNotBlank("jobName")
    val leaderElection = RedissonLeaderElector(this, options)
    return leaderElection.runAsyncIfLeader(jobName, executor) { action() }
}
