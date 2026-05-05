package io.bluetape4k.leader.lettuce

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import io.bluetape4k.leader.lettuce.lock.LettuceSuspendLock
import io.bluetape4k.support.requireNotBlank
import io.lettuce.core.api.StatefulRedisConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * [StatefulRedisConnection]에서 [LettuceSuspendLeaderElector] 인스턴스를 생성합니다.
 *
 * ```kotlin
 * val election = connection.suspendLeaderElection()
 * val result = election.runIfLeader("daily-job") { "done" }
 * ```
 *
 * @param options 리더 선출 옵션 (기본값: [LeaderElectionOptions.Default])
 * @return [LettuceSuspendLeaderElector] 인스턴스
 */
fun StatefulRedisConnection<String, String>.suspendLeaderElection(
    options: LeaderElectionOptions = LeaderElectionOptions.Default,
): LettuceSuspendLeaderElector =
    LettuceSuspendLeaderElector(this, options)


/**
 * Lettuce Redis 클라이언트를 이용한 코루틴 기반 리더 선출 구현체입니다.
 *
 * [LettuceSuspendLock]을 사용하여 비동기적으로 리더를 선출합니다.
 *
 * ```kotlin
 * val election = LettuceSuspendLeaderElection(connection)
 * val result = election.runIfLeader("daily-job") { "done" }
 * ```
 *
 * @param connection Lettuce [StatefulRedisConnection] (StringCodec 기반)
 * @param options    리더 선출 옵션 (waitTime, leaseTime)
 */
class LettuceSuspendLeaderElector(
    private val connection: StatefulRedisConnection<String, String>,
    val options: LeaderElectionOptions = LeaderElectionOptions.Default,
): SuspendLeaderElector {

    companion object: KLogging()

    override suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T? {
        lockName.requireNotBlank("lockName")

        val lock = LettuceSuspendLock(connection, lockName, options.leaseTime)
        val acquired = lock.tryLock(options.waitTime, options.leaseTime)
        if (!acquired) {
            log.debug { "리더 선출 실패 (슬롯 없음, suspend): lockName=$lockName" }
            return null
        }
        log.debug { "리더 선출 성공 (suspend): lockName=$lockName" }
        try {
            return action()
        } finally {
            // NonCancellable: 코루틴 취소 시에도 락 해제가 중단되지 않도록 보호
            withContext(NonCancellable) {
                runCatching { if (lock.isHeldByCurrentInstance()) lock.unlock() }
                    .onFailure { error ->
                        if (error is CancellationException) throw error
                        log.warn(error) { "Fail to release lock. lockName=$lockName" }
                    }
            }
        }
    }
}
