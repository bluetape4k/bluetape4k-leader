package io.bluetape4k.leader.hazelcast

import com.hazelcast.core.HazelcastInstance
import com.hazelcast.map.IMap
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.coroutines.SuspendLeaderElection
import io.bluetape4k.leader.hazelcast.lock.HazelcastSuspendLock
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requireNotBlank
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * [HazelcastInstance]의 [IMap] 분산 락을 이용한 코루틴 기반 리더 선출 구현체입니다.
 *
 * 토큰 기반 락(`putIfAbsent` + TTL)으로 코루틴 스레드 전환과 무관하게 안전하게 동작합니다.
 *
 * ```kotlin
 * val election = HazelcastSuspendLeaderElection(hazelcastInstance)
 * val result = election.runIfLeader("daily-job") {
 *     delay(100)
 *     processData()
 * }
 * ```
 *
 * @param hazelcast Hazelcast 클라이언트 인스턴스
 * @param options 리더 선출 옵션 (waitTime, leaseTime)
 */
class HazelcastSuspendLeaderElection private constructor(
    private val hazelcast: HazelcastInstance,
    private val options: LeaderElectionOptions,
): SuspendLeaderElection {

    companion object: KLoggingChannel() {
        @JvmStatic
        operator fun invoke(
            hazelcast: HazelcastInstance,
            options: LeaderElectionOptions = LeaderElectionOptions.Default,
        ): HazelcastSuspendLeaderElection = HazelcastSuspendLeaderElection(hazelcast, options)
    }

    private val lockMap: IMap<String, String> = hazelcast.getMap(HazelcastLeaderElection.LOCK_MAP_NAME)

    override suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T? {
        lockName.requireNotBlank("lockName")

        val lock = HazelcastSuspendLock(lockMap, lockName)
        log.debug { "Leader 승격을 요청합니다 (suspend) ... lockName=$lockName" }

        val acquired = lock.tryLock(options.waitTime, options.leaseTime)
        if (!acquired) {
            log.debug { "Leader 승격 실패 (슬롯 없음, suspend). lockName=$lockName" }
            return null
        }

        log.debug { "Leader로 승격하여 suspend 작업을 수행합니다. lockName=$lockName" }
        try {
            return action()
        } finally {
            // NonCancellable: 코루틴 취소 시에도 락 해제가 중단되지 않도록 보호
            withContext(NonCancellable) {
                if (lock.isHeldByCurrentInstance()) {
                    try {
                        lock.unlock()
                        log.debug { "Leader 권한을 반납했습니다 (suspend). lockName=$lockName" }
                    } catch (e: Exception) {
                        log.warn(e) { "Fail to release lock (suspend). lockName=$lockName" }
                    }
                }
            }
        }
    }
}

/**
 * Hazelcast 분산 락을 이용하여 리더로 선출된 경우에만 suspend [action]을 실행합니다.
 */
suspend inline fun <T> HazelcastInstance.suspendRunIfLeader(
    jobName: String,
    options: LeaderElectionOptions = LeaderElectionOptions.Default,
    crossinline action: suspend () -> T,
): T? {
    jobName.requireNotBlank("jobName")
    return HazelcastSuspendLeaderElection(this, options).runIfLeader(jobName) { action() }
}
