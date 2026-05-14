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
import java.util.concurrent.Executor
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.TimeUnit

/**
 * Redisson 분산 락을 이용하여 여러 프로세스/스레드 중 단 하나만 작업을 수행하도록 리더를 선출합니다.
 *
 * ## 동작 (T8 PR 3)
 * - [runIfLeader]: 동기 방식으로 락을 획득한 뒤 [LeaderElector.runIfLeader]를 실행하고, 완료 후 락을 해제합니다.
 * - [runAsyncIfLeader]: `tryLockAsync`로 비동기 락을 획득하고, `CompletableFuture` 완료 시 [RLock.unlockAsync]로 락을 해제합니다.
 * - [LeaderElectionOptions.waitTime] 내 락 획득에 실패하면 `null`을 반환합니다 (ShedLock skip 방식).
 * - 락 대기 중 인터럽트가 발생하면 [org.redisson.client.RedisException]으로 래핑되어 전파됩니다.
 *
 * ## ExtendDelegate 통합
 *
 * - acquire 후 [RedissonLockExtendDelegate] 를 생성하여 [LeaderLockHandle.Real] + watchdog 와 동일 reference 공유 (AC-15).
 * - aspect 가 `LockExtender.extendActiveLock` 호출 시 동일 delegate 를 통해 `RLock.expire(d)` 실행.
 * - autoExtend 여부와 무관하게 항상 명시적 `leaseTime` 으로 acquire — Redisson 내장 watchdog 비활성화.
 *   [LeaderLeaseAutoExtender] 가 단일 watchdog 으로 동작하여 R2 watchdog skip semantics 보장.
 *
 * ```kotlin
 * val election = RedissonLeaderElector(redissonClient)
 * val result = election.runIfLeader("my-job") {
 *     processData()
 * }
 * ```
 *
 * @param redissonClient Redisson 클라이언트
 * @param options 리더 선출 옵션 (waitTime, leaseTime)
 * @see RedissonSuspendLeaderElector Coroutine 환경에서 사용할 suspend 버전
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
                        executeActionAsync(lock, currentThreadId, executor, System.nanoTime(), action)
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
     * 락을 보유한 상태에서 비동기 [action]을 실행하고, 완료(성공/실패) 후 락을 해제합니다.
     *
     * Lettuce 패턴 정합 — async path 에서는 handle push 를 수행하지 않습니다 (AOP scope 는 sync/suspend 만 지원).
     */
    private inline fun <T> executeActionAsync(
        lock: RLock,
        currentThreadId: Long,
        executor: Executor,
        acquiredAtNanos: Long,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> {
        val lockName = lock.name
        val delegate = RedissonLockExtendDelegate(redissonClient, lock, currentThreadId)
        val watchdog = LeaderLeaseAutoExtender.start(
            options.autoExtend,
            options.leaseTime,
            delegate,
            ERROR_CLASSIFIER,
        )
        log.debug { "Leader로 승격하여 비동기 작업을 수행합니다. lock=$lockName, threadId=$currentThreadId" }

        val actionFuture = runCatching { action() }
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
 * Redisson 분산 락을 이용하여 리더 선출을 통한 작업을 수행합니다.
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
 * Redisson 분산 락을 이용하여 리더 선출을 통한 비동기 작업을 수행합니다.
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
