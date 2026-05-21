package io.bluetape4k.leader.redisson

import io.bluetape4k.leader.AopScopeAccess
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderLeaseAutoExtender
import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.leader.internal.CompositeBackendErrorClassifier
import io.bluetape4k.leader.internal.SuspendExtendDelegate
import io.bluetape4k.leader.redisson.internal.RedissonBackendErrorClassifier
import io.bluetape4k.leader.redisson.internal.RedissonSuspendLockExtendDelegate
import io.bluetape4k.leader.remainingMinLeaseTime
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requireNotBlank
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import org.redisson.api.RLock
import org.redisson.api.RedissonClient
import org.redisson.client.RedisException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Executes a suspend action via leader election using a Redisson distributed lock.
 *
 * When the lock is acquired, [action] is executed and the lock is released after completion.
 * Returns `null` when the lock cannot be acquired within [LeaderElectionOptions.waitTime] (ShedLock skip semantics).
 * An interrupt while waiting for the lock is propagated wrapped as [org.redisson.client.RedisException].
 *
 * ```kotlin
 * val result = redissonClient.suspendRunIfLeader("my-job") {
 *     delay(100)
 *     42
 * }
 * ```
 *
 * @param jobName job name (used as the distributed lock key)
 * @param options leader election options (waitTime, leaseTime)
 * @param action the suspend action to execute when elected leader
 * @return [action] result
 * @see RedissonSuspendLeaderElector
 */
suspend inline fun <T> RedissonClient.suspendRunIfLeader(
    jobName: String,
    options: LeaderElectionOptions = LeaderElectionOptions.Default,
    crossinline action: suspend () -> T,
): T? {
    jobName.requireNotBlank("jobName")

    val leaderElection = RedissonSuspendLeaderElector(this, options)
    return leaderElection.runIfLeader(jobName) { action() }
}


/**
 * Elects a leader among multiple processes/threads using a Redisson distributed lock,
 * ensuring only one executes the action. This is the suspend variant for use in coroutine contexts.
 *
 * ## Behavior / Contract (T8 PR 3)
 *
 * - After acquire, creates [RedissonSuspendLockExtendDelegate] and shares the same reference
 *   with [LeaderLockHandle.Real] and the watchdog (AC-15).
 * - The aspect's `LockExtenderSuspend.extendActiveLockSuspend` uses the same delegate reference.
 * - Propagates the handle to `coroutineContext` via `withContext(AopScopeAccess.createLockHandleElement(handle))`.
 * - Always acquires with an explicit `leaseTime` regardless of `autoExtend` — disables Redisson's built-in watchdog.
 *
 * ## Why PID-seeded Snowflake ID instead of threadId
 * Redisson's [RLock] identifies lock owners by thread ID. However, coroutines may run on different
 * threads, so the thread acquiring the lock may differ from the thread releasing it. To solve this,
 * an ID of the form `timestamp | pid%(2^10) | seq` is generated, providing collision-free lock owner
 * identification across different processes on the same machine and different coroutines in the same
 * process — with no Redis round-trip.
 *
 * ```kotlin
 * val election = RedissonSuspendLeaderElector(redissonClient)
 * val result = election.runIfLeader("my-job") {
 *     delay(100)
 *     processData()
 * }
 * ```
 *
 * @param redissonClient Redisson client
 * @param options leader election options (waitTime, leaseTime)
 * @see RedissonLeaderElector sync/async (CompletableFuture) variant
 */
class RedissonSuspendLeaderElector private constructor(
    private val redissonClient: RedissonClient,
    private val options: LeaderElectionOptions,
): SuspendLeaderElector {

    companion object: KLoggingChannel() {
        internal const val REDISSON_SUSPEND_FACTORY_BEAN_NAME = "redisson-suspend-leader-elector"
        internal val ERROR_CLASSIFIER = CompositeBackendErrorClassifier(RedissonBackendErrorClassifier)

        // PID-seeded Snowflake-like ID 생성기
        // timestamp(42bit) | pid%(2^10)(10bit) | seq(12bit)
        private val machineId = ProcessHandle.current().pid() and 0x3FFL  // 10비트
        private val lockIdSeq = AtomicLong(0L)

        private fun nextLockId(): Long {
            val ts = System.currentTimeMillis() shl 22
            val mid = machineId shl 12
            val seq = lockIdSeq.getAndIncrement() and 0xFFFL  // 12비트
            return ts or mid or seq
        }

        operator fun invoke(
            redissonClient: RedissonClient,
            options: LeaderElectionOptions = LeaderElectionOptions.Default,
        ): RedissonSuspendLeaderElector {
            return RedissonSuspendLeaderElector(redissonClient, options)
        }
    }

    private val waitTimeMills = options.waitTime.inWholeMilliseconds
    private val leaseTimeMills = options.leaseTime.inWholeMilliseconds

    /**
     * Executes [action] when elected leader using a Redisson lock; does nothing otherwise.
     *
     * Uses a PID-seeded Snowflake-like ID (`timestamp | pid%(2^10) | seq`) as the lock identifier
     * instead of `Thread.currentThread().threadId()` to prevent lock owner mismatch caused by
     * thread switching in coroutine contexts.
     *
     * @param lockName lock name — acquiring this lock promotes the node to leader
     * @param action the suspend code block to execute when promoted to leader
     * @return [action] result, or `null` when the leader lock cannot be acquired
     * @throws org.redisson.client.RedisException when an interrupt occurs while waiting for the lock
     */
    override suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T? =
        runImpl(lockName, auditLeaderId = null, action)

    override suspend fun <T> runIfLeader(slot: LeaderSlot, action: suspend () -> T): T? =
        runImpl(slot.lockName, auditLeaderId = slot.leaderId, action)

    override suspend fun <T> runIfLeaderResultSuspend(slot: LeaderSlot, action: suspend () -> T): LeaderRunResult<T> {
        var elected = false
        val value = try {
            runImpl(slot.lockName, auditLeaderId = slot.leaderId) {
                elected = true
                action()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (elected) {
                return LeaderRunResult.ActionFailed(e)
            }
            throw e
        }
        return if (elected) LeaderRunResult.Elected(value, leaderId = slot.leaderId) else LeaderRunResult.Skipped
    }

    private suspend fun <T> runImpl(lockName: String, auditLeaderId: String?, action: suspend () -> T): T? {
        lockName.requireNotBlank("lockName")

        val lock: RLock = redissonClient.getLock(lockName)

        try {
            log.debug { "Leader 승격을 요청합니다 ..." }

            val lockId = nextLockId()

            // T8: 항상 명시적 leaseTime 사용 — Redisson 내장 watchdog 비활성화.
            val acquired = lock
                .tryLockAsync(waitTimeMills, leaseTimeMills, TimeUnit.MILLISECONDS, lockId)
                .await()

            if (!acquired) {
                log.debug { "Leader 승격 실패 (슬롯 없음). lock=$lockName" }
                return null
            }
            val acquiredAtNanos = System.nanoTime()
            val delegate: SuspendExtendDelegate = RedissonSuspendLockExtendDelegate(redissonClient, lock, lockId)
            val identity = LockIdentity(
                lockName = lockName,
                kind = LockIdentity.AnnotationKind.SINGLE,
                factoryBeanName = REDISSON_SUSPEND_FACTORY_BEAN_NAME,
            )
            val handle = LeaderLockHandle.real(
                identity = identity,
                token = lockName,
                acquiredAtNanos = acquiredAtNanos,
                acquiringThreadId = lockId,
                extendDelegate = delegate,
                auditLeaderId = auditLeaderId,
            )
            val watchdog = LeaderLeaseAutoExtender.start(
                options.autoExtend,
                options.leaseTime,
                delegate,
                ERROR_CLASSIFIER,
            )
            log.debug { "Leader로 승격되어 작업을 수행합니다. lock=$lockName, lockId=$lockId" }
            try {
                return withContext(AopScopeAccess.createLockHandleElement(handle)) {
                    action()
                }
            } finally {
                // NonCancellable: 코루틴 취소 시에도 lease 정리가 중단되지 않도록 보호
                withContext(NonCancellable) {
                    watchdog.close()
                    if (lock.isHeldByThread(lockId)) {
                        try {
                            releaseLock(lock, lockId, acquiredAtNanos)
                            log.debug { "작업이 완료되어 Leader 권한을 반납했습니다. lock=$lockName, lockId=$lockId" }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            log.warn(e) { "Fail to release lock. lock=$lockName, lockId=$lockId" }
                        }
                    }
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            log.warn(e) { "Interrupt to run action as leader. lockName=$lockName" }
            throw RedisException("Interrupted while acquiring lock. lock=$lockName", e)
        }
    }

    private suspend fun releaseLock(lock: RLock, lockId: Long, acquiredAtNanos: Long) {
        val remaining = remainingMinLeaseTime(acquiredAtNanos, options.minLeaseTime)
        if (remaining > kotlin.time.Duration.ZERO) {
            withContext(Dispatchers.IO) {
                redissonClient.keys.expire(remaining.toJavaDuration(), lock.name)
            }
        } else {
            lock.unlockAsync(lockId).await()
        }
    }

    private fun kotlin.time.Duration.toJavaDuration(): java.time.Duration =
        java.time.Duration.ofNanos(inWholeNanoseconds)
}
