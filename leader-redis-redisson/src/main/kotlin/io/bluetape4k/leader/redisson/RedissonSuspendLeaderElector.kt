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
 * Redisson 분산 락을 이용하여 리더 선출을 통한 suspend 작업을 실행합니다.
 *
 * 락 획득에 성공하면 [action]을 실행하고, 완료 후 락을 해제합니다.
 * [LeaderElectionOptions.waitTime] 내 락 획득에 실패하면 `null`을 반환합니다 (ShedLock skip 방식).
 * 락 대기 중 인터럽트가 발생하면 [org.redisson.client.RedisException]으로 래핑되어 전파됩니다.
 *
 * ```kotlin
 * val result = redissonClient.suspendRunIfLeader("my-job") {
 *     delay(100)
 *     42
 * }
 * ```
 *
 * @param jobName 작업 이름 (분산 락 키로 사용)
 * @param options 리더 선출 옵션 (waitTime, leaseTime)
 * @param action 리더로 선출되었을 때 실행할 suspend 작업
 * @return [action] 실행 결과
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
 * Redisson 분산 락을 이용하여 여러 프로세스/스레드 중 단 하나만 작업을 수행하도록 리더를 선출합니다.
 * Coroutine 환경에서 사용할 수 있는 suspend 버전입니다.
 *
 * ## 동작/계약 (T8 PR 3)
 *
 * - acquire 후 [RedissonSuspendLockExtendDelegate] 를 생성하여 [LeaderLockHandle.Real] + watchdog 와 동일 reference 공유 (AC-15).
 * - aspect 의 `LockExtenderSuspend.extendActiveLockSuspend` 는 동일 delegate reference 를 사용합니다.
 * - `withContext(AopScopeAccess.createLockHandleElement(handle))` 로 coroutineContext 에 handle 전파.
 * - autoExtend 여부와 무관하게 항상 명시적 `leaseTime` 으로 acquire — Redisson 내장 watchdog 비활성화.
 *
 * ## threadId 대신 PID-seeded Snowflake ID를 사용하는 이유
 * Redisson의 [RLock]은 락 소유자를 스레드 ID로 식별합니다.
 * 그러나 Coroutine은 여러 스레드를 오가며 실행되므로, 락 획득 시점의 스레드와
 * 락 해제 시점의 스레드가 달라질 수 있습니다.
 * 이를 해결하기 위해 `timestamp | pid%(2^10) | seq` 형태의 ID를 생성하여
 * 같은 머신의 다른 프로세스, 같은 프로세스의 다른 코루틴 사이에서 충돌 없이
 * 락 소유자를 식별합니다. (Redis 왕복 없음)
 *
 * ```kotlin
 * val election = RedissonSuspendLeaderElector(redissonClient)
 * val result = election.runIfLeader("my-job") {
 *     delay(100)
 *     processData()
 * }
 * ```
 *
 * @param redissonClient Redisson 클라이언트
 * @param options 리더 선출 옵션 (waitTime, leaseTime)
 * @see RedissonLeaderElector 동기/비동기(CompletableFuture) 버전
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
     * Redisson Lock을 이용하여, 리더로 선출되면 [action]을 수행하고, 그렇지 않다면 수행하지 않습니다.
     *
     * Coroutine 환경에서 스레드 전환으로 인한 락 소유자 불일치를 방지하기 위해,
     * `Thread.currentThread().threadId()` 대신 PID-seeded Snowflake-like ID
     * (`timestamp | pid%(2^10) | seq`)를 락 식별자로 사용합니다.
     *
     * @param lockName 락 이름 — 락 획득에 성공하면 리더로 승격됩니다.
     * @param action 리더로 승격되었을 때 수행할 suspend 코드 블록
     * @return [action] 실행 결과, 리더 획득 실패 시 `null`
     * @throws org.redisson.client.RedisException 락 대기 중 인터럽트가 발생한 경우
     */
    override suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T? =
        runImpl(lockName, auditLeaderId = null, action)

    override suspend fun <T> runIfLeader(slot: LeaderSlot, action: suspend () -> T): T? =
        runImpl(slot.lockName, auditLeaderId = slot.leaderId, action)

    override suspend fun <T> runIfLeaderResultSuspend(slot: LeaderSlot, action: suspend () -> T): LeaderRunResult<T> {
        var elected = false
        val value = runImpl(slot.lockName, auditLeaderId = slot.leaderId) { elected = true; action() }
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
            val delegate = RedissonSuspendLockExtendDelegate(redissonClient, lock, lockId)
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
