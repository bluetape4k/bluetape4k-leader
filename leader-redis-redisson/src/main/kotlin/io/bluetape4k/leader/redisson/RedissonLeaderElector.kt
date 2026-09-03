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
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider
import io.bluetape4k.leader.internal.CompositeBackendErrorClassifier
import io.bluetape4k.leader.internal.LeaderFutureBridge
import io.bluetape4k.leader.redisson.internal.RedissonBackendErrorClassifier
import io.bluetape4k.leader.redisson.internal.RedissonLockExtendDelegate
import io.bluetape4k.leader.remainingMinLeaseTime
import io.bluetape4k.leader.validateLockName
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.error
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
 * `RedissonLeaderElector`는 Redis Redisson backend의 leader election, lock lease, ownership 확인을 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @property redissonClient Redis Redisson backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property options Redis Redisson backend 호출과 상태 계산에 사용하는 속성입니다.
 */
// Single elector는 sync/async, lease lifecycle, diagnostics 계약을 함께 구현합니다.
@Suppress("TooManyFunctions")
class RedissonLeaderElector private constructor(
    private val redissonClient: RedissonClient,
    private val options: LeaderElectionOptions,
): LeaderElector,
    LeaderBackendDiagnosticsProvider by RedissonLeaderBackendDiagnostics(redissonClient),
    io.bluetape4k.leader.LeaderLeaseAcquirerSupport {

    override val leaseAcquirerDelegate: io.bluetape4k.leader.LeaderLeaseAcquirer by lazy {
        io.bluetape4k.leader.internal.LeaderElectorLeaseAdapter({ this }, options)
    }

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
        validateLockName(lockName)

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
        return LeaderFutureBridge.map(runAsyncIfLeader(slot, executor) {
            elected.set(true)
            action()
        }) { value, failure ->
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
        validateLockName(lockName)

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
     * `선언` 호출은 Redis Redisson backend leader election 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
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
                return releaseAndPropagate(lock, currentThreadId, acquiredAtNanos, watchdog, error, null)
            }

        return actionFuture
            .handleAsync<Pair<T?, Throwable?>>({ value, error -> Pair(value, error) }, executor)
            .thenCompose { (value, error) ->
                releaseAndPropagate(lock, currentThreadId, acquiredAtNanos, watchdog, error, value)
            }
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

    private fun <T> releaseAndPropagate(
        lock: RLock,
        currentThreadId: Long,
        acquiredAtNanos: Long,
        watchdog: AutoCloseable,
        error: Throwable?,
        value: T?,
    ): CompletableFuture<T?> {
        watchdog.close()
        return releaseLockAsync(lock, currentThreadId, acquiredAtNanos)
            .exceptionally { releaseError ->
                log.error(releaseError) { "Fail to release lock. lock=${lock.name}, threadId=$currentThreadId" }
            }
            .thenCompose {
                if (error != null) {
                    CompletableFuture.failedFuture(error)
                } else {
                    CompletableFuture.completedFuture(value)
                }
            }
    }

    private fun releaseLockAsync(lock: RLock, currentThreadId: Long, acquiredAtNanos: Long): CompletableFuture<Unit> {
        val lockName = lock.name
        return try {
            if (lock.isHeldByThread(currentThreadId)) {
                val remaining = remainingMinLeaseTime(acquiredAtNanos, options.minLeaseTime)
                val releaseFuture: CompletableFuture<*> = if (remaining > kotlin.time.Duration.ZERO) {
                    CompletableFuture.supplyAsync {
                        redissonClient.keys.expire(remaining.toJavaDuration(), lockName)
                    }
                } else {
                    lock.unlockAsync(currentThreadId).toCompletableFuture()
                }

                releaseFuture.thenApply {
                    log.debug { "Leader 권한을 반납했습니다. lock=$lockName, threadId=$currentThreadId" }
                    Unit
                }
            } else {
                CompletableFuture.completedFuture(Unit)
            }
        } catch (e: Throwable) {
            failedCompletableFutureOf(e)
        }
    }

    private fun kotlin.time.Duration.toJavaDuration(): java.time.Duration =
        java.time.Duration.ofNanos(inWholeNanoseconds)
}


/**
 * `선언` 호출은 Redis Redisson backend leader election 계약의 일부 동작을 수행합니다.
 *
 * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
 */
inline fun <T> RedissonClient.runIfLeader(
    jobName: String,
    options: LeaderElectionOptions = LeaderElectionOptions.Default,
    crossinline action: () -> T,
): T? {
    validateLockName(jobName)
    val leaderElection = RedissonLeaderElector(this, options)
    return leaderElection.runIfLeader(jobName) { action() }
}

/**
 * `선언` 호출은 Redis Redisson backend leader election 계약의 일부 동작을 수행합니다.
 *
 * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
 */
inline fun <T> RedissonClient.runAsyncIfLeader(
    jobName: String,
    executor: Executor = ForkJoinPool.commonPool(),
    options: LeaderElectionOptions = LeaderElectionOptions.Default,
    crossinline action: () -> CompletableFuture<T>,
): CompletableFuture<T?> {
    validateLockName(jobName)
    val leaderElection = RedissonLeaderElector(this, options)
    return leaderElection.runAsyncIfLeader(jobName, executor) { action() }
}
