package io.bluetape4k.leader.redisson

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.coroutines.SuspendLeaderElection
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requireNotBlank
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import org.redisson.api.RLock
import org.redisson.api.RedissonClient
import org.redisson.client.RedisException
import java.util.concurrent.TimeUnit

/**
 * Redisson 분산 락을 이용하여 리더 선출을 통한 suspend 작업을 실행합니다.
 *
 * 락 획득에 성공하면 [action]을 실행하고, 완료 후 락을 해제합니다.
 * [LeaderElectionOptions.waitTime] 내 락 획득에 실패하면 `null`을 반환합니다 (ShedLock skip 방식).
 * 락 대기 중 인터럽트가 발생하면 [org.redisson.client.RedisException]으로 래핑되어 전파됩니다.
 *
 * ```kotlin
 * val result = redissonClient.suspendRunIfLeader("my-job") {
 *     // 리더로 선출된 경우에만 실행되는 suspend 작업
 *     delay(100)
 *     42
 * }
 * ```
 *
 * @param jobName 작업 이름 (분산 락 키로 사용)
 * @param options 리더 선출 옵션 (waitTime, leaseTime)
 * @param action 리더로 선출되었을 때 실행할 suspend 작업
 * @return [action] 실행 결과
 * @see RedissonSuspendLeaderElection
 */
suspend inline fun <T> RedissonClient.suspendRunIfLeader(
    jobName: String,
    options: LeaderElectionOptions = LeaderElectionOptions.Default,
    crossinline action: suspend () -> T,
): T? {
    jobName.requireNotBlank("jobName")

    val leaderElection = RedissonSuspendLeaderElection(this, options)
    return leaderElection.runIfLeader(jobName) { action() }
}


/**
 * Redisson 분산 락을 이용하여 여러 프로세스/스레드 중 단 하나만 작업을 수행하도록 리더를 선출합니다.
 * Coroutine 환경에서 사용할 수 있는 suspend 버전입니다.
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
 * val election = RedissonSuspendLeaderElection(redissonClient)
 * val result = election.runIfLeader("my-job") {
 *     // 리더로 선출된 경우에만 실행되는 suspend 작업
 *     delay(100)
 *     processData()
 * }
 * ```
 *
 * @param redissonClient Redisson 클라이언트
 * @param options 리더 선출 옵션 (waitTime, leaseTime)
 * @see RedissonLeaderElection 동기/비동기(CompletableFuture) 버전
 */
class RedissonSuspendLeaderElection private constructor(
    private val redissonClient: RedissonClient,
    options: LeaderElectionOptions,
): SuspendLeaderElection {

    companion object: KLoggingChannel() {
        // PID-seeded Snowflake-like ID 생성기
        // timestamp(42bit) | pid%(2^10)(10bit) | seq(12bit)
        // - 단일 JVM: seq 단조 증가로 충돌 없음
        // - HA (동일 머신 다중 프로세스): pid % 1024 가 달라 충돌 없음
        // - HA (다중 머신): 같은 pid % 1024 가 같은 ms 에 같은 seq 를 가질 경우만 충돌 (무시할 확률)
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
        ): RedissonSuspendLeaderElection {
            return RedissonSuspendLeaderElection(redissonClient, options)
        }
    }

    private val waitTimeMills = options.waitTime.toMillis()
    private val leaseTimeMills = options.leaseTime.toMillis()

    /**
     * Redisson Lock을 이용하여, 리더로 선출되면 [action]을 수행하고, 그렇지 않다면 수행하지 않습니다.
     *
     * Coroutine 환경에서 스레드 전환으로 인한 락 소유자 불일치를 방지하기 위해,
     * `Thread.currentThread().threadId()` 대신 PID-seeded Snowflake-like ID
     * (`timestamp | pid%(2^10) | seq`, 동반자 객체의 `nextLockId()` 참고)를 락 식별자로 사용합니다.
     *
     * @param lockName 락 이름 — 락 획득에 성공하면 리더로 승격됩니다.
     * @param action 리더로 승격되었을 때 수행할 suspend 코드 블록
     * @return [action] 실행 결과, 리더 획득 실패 시 `null`
     * @throws org.redisson.client.RedisException 락 대기 중 인터럽트가 발생한 경우
     */
    override suspend fun <T> runIfLeader(
        lockName: String,
        action: suspend () -> T,
    ): T? {
        lockName.requireNotBlank("lockName")

        val lock: RLock = redissonClient.getLock(lockName)

        try {
            log.debug { "Leader 승격을 요청합니다 ..." }

            val lockId = nextLockId()

            val acquired = lock
                .tryLockAsync(
                    waitTimeMills,
                    leaseTimeMills,
                    TimeUnit.MILLISECONDS,
                    lockId
                )
                .await()

            if (acquired) {
                log.debug { "Leader로 승격되어 작업을 수행합니다. lock=$lockName, lockId=$lockId" }
                try {
                    return action()
                } finally {
                    if (lock.isHeldByThread(lockId)) {
                        // NonCancellable: 코루틴 취소 시에도 락 해제가 중단되지 않도록 보호
                        withContext(NonCancellable) {
                            runCatching { lock.unlockAsync(lockId).await() }
                                .onSuccess {
                                    log.debug { "작업이 완료되어 Leader 권한을 반납했습니다. lock=$lockName, lockId=$lockId" }
                                }
                                .onFailure { error ->
                                    if (error is CancellationException) throw error
                                    log.warn(error) { "Fail to release lock. lock=$lockName, lockId=$lockId" }
                                }
                        }
                    }
                }
            } else {
                log.debug { "Leader 승격 실패 (슬롯 없음). lock=$lockName" }
                return null
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            log.warn(e) { "Interrupt to run action as leader. lockName=$lockName" }
            throw RedisException("Interrupted while acquiring lock. lock=$lockName", e)
        }
    }
}
