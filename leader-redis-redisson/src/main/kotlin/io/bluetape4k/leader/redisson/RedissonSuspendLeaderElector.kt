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
import io.bluetape4k.leader.validateLockName
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
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
 * `선언` 호출은 Redis Redisson backend leader election 계약의 일부 동작을 수행합니다.
 *
 * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
 */
suspend inline fun <T> RedissonClient.suspendRunIfLeader(
    jobName: String,
    options: LeaderElectionOptions = LeaderElectionOptions.Default,
    crossinline action: suspend () -> T,
): T? {
    validateLockName(jobName)

    val leaderElection = RedissonSuspendLeaderElector(this, options)
    return leaderElection.runIfLeader(jobName) { action() }
}


/**
 * `RedissonSuspendLeaderElector`는 Redis Redisson backend의 leader election, lock lease, ownership 확인을 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @property redissonClient Redis Redisson backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property options Redis Redisson backend 호출과 상태 계산에 사용하는 속성입니다.
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
     * `선언` 호출은 Redis Redisson backend leader election 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
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
        validateLockName(lockName)

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
            var watchdog: AutoCloseable? = null
            try {
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
                watchdog = LeaderLeaseAutoExtender.start(
                    options.autoExtend,
                    options.leaseTime,
                    delegate,
                    ERROR_CLASSIFIER,
                )
                log.debug { "Leader로 승격되어 작업을 수행합니다. lock=$lockName, lockId=$lockId" }
                return withContext(AopScopeAccess.createLockHandleElement(handle)) {
                    action()
                }
            } finally {
                // NonCancellable: 코루틴 취소 시에도 lease 정리가 중단되지 않도록 보호
                withContext(NonCancellable) {
                    watchdog?.close()
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
