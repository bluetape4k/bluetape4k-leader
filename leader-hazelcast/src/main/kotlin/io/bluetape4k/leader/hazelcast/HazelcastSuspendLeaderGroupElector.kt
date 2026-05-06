package io.bluetape4k.leader.hazelcast

import com.hazelcast.core.HazelcastInstance
import com.hazelcast.map.IMap
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LeaderGroupState
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElector
import io.bluetape4k.leader.hazelcast.lock.HazelcastSuspendLock
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * [IMap] 슬롯 기반 분산 세마포어를 이용한 코루틴 기반 복수 리더 선출 구현체입니다.
 *
 * ```kotlin
 * val election = HazelcastSuspendLeaderGroupElector(hazelcastInstance, LeaderGroupElectionOptions(maxLeaders = 3))
 * val result = election.runIfLeader("batch-job") {
 *     delay(100)
 *     processChunk()
 * }
 * ```
 *
 * @param hazelcast Hazelcast 클라이언트 인스턴스
 * @param options 리더 그룹 선출 옵션 (maxLeaders, waitTime, leaseTime)
 */
class HazelcastSuspendLeaderGroupElector private constructor(
    private val hazelcast: HazelcastInstance,
    options: LeaderGroupElectionOptions,
): SuspendLeaderGroupElector {

    companion object: KLoggingChannel() {
        @JvmStatic
        operator fun invoke(
            hazelcast: HazelcastInstance,
            options: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
        ): HazelcastSuspendLeaderGroupElector {
            options.maxLeaders.requirePositiveNumber("maxLeaders")
            return HazelcastSuspendLeaderGroupElector(hazelcast, options)
        }
    }

    override val maxLeaders: Int = options.maxLeaders
    private val waitTime = options.waitTime
    private val leaseTime = options.leaseTime

    private val lockMap: IMap<String, String> = hazelcast.getMap(HazelcastLeaderGroupElector.LOCK_MAP_NAME)

    private fun slotKey(lockName: String, slot: Int) = "$lockName:slot:$slot"

    override fun activeCount(lockName: String): Int =
        (0 until maxLeaders).count { slot -> lockMap.containsKey(slotKey(lockName, slot)) }

    override fun availableSlots(lockName: String): Int = maxLeaders - activeCount(lockName)

    override fun state(lockName: String): LeaderGroupState =
        LeaderGroupState(lockName, maxLeaders, activeCount(lockName))

    override suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T? {
        lockName.requireNotBlank("lockName")

        val slotWaitTime = waitTime.dividedBy(maxLeaders.toLong())
        log.debug { "리더 그룹 슬롯 획득을 요청합니다 (suspend). lockName=$lockName, maxLeaders=$maxLeaders" }

        var acquiredLock: HazelcastSuspendLock? = null
        for (slot in 0 until maxLeaders) {
            currentCoroutineContext().ensureActive()
            val lock = HazelcastSuspendLock(lockMap, slotKey(lockName, slot))
            if (lock.tryLock(slotWaitTime, leaseTime)) {
                acquiredLock = lock
                break
            }
        }

        if (acquiredLock == null) {
            log.debug { "리더 그룹 슬롯 획득 실패 (슬롯 없음, suspend). lockName=$lockName" }
            return null
        }

        log.debug { "리더 그룹 슬롯을 획득하여 suspend 작업을 수행합니다. lockName=$lockName" }
        try {
            return action()
        } finally {
            withContext(NonCancellable) {
                if (acquiredLock.isHeldByCurrentInstance()) {
                    try {
                        acquiredLock.unlock()
                        log.debug { "리더 그룹 슬롯을 반납했습니다 (suspend). lockName=$lockName" }
                    } catch (e: Exception) {
                        log.warn(e) { "Fail to release group slot (suspend). lockName=$lockName" }
                    }
                }
            }
        }
    }
}

/**
 * Hazelcast 분산 세마포어(슬롯 기반)를 이용하여 최대 [options.maxLeaders]개의 리더로 선출된 경우에만 suspend [action]을 실행합니다.
 */
suspend inline fun <T> HazelcastInstance.suspendRunIfLeaderGroup(
    lockName: String,
    options: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
    crossinline action: suspend () -> T,
): T? {
    lockName.requireNotBlank("lockName")
    return HazelcastSuspendLeaderGroupElector(this, options).runIfLeader(lockName) { action() }
}
