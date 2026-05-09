package io.bluetape4k.leader.hazelcast

import com.hazelcast.core.HazelcastInstance
import com.hazelcast.map.IMap
import io.bluetape4k.leader.LeaderGroupElector
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LeaderGroupState
import io.bluetape4k.leader.hazelcast.lock.HazelcastLock
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.error
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/**
 * [IMap] 슬롯 기반 분산 세마포어를 이용한 복수 리더 선출 구현체입니다.
 *
 * `${lockName}:slot:N` 키를 사용하는 [HazelcastLock] N개로 `maxLeaders` 슬롯을 시뮬레이션합니다.
 * CP Subsystem 없이 동작하며 토큰 기반이므로 스레드에 귀속되지 않습니다.
 *
 * ```kotlin
 * val election = HazelcastLeaderGroupElector(hazelcastInstance, LeaderGroupElectionOptions(maxLeaders = 3))
 * val result = election.runIfLeader("batch-job") { processChunk() }
 * ```
 *
 * @param hazelcast Hazelcast 클라이언트 인스턴스
 * @param options 리더 그룹 선출 옵션 (maxLeaders, waitTime, leaseTime)
 */
class HazelcastLeaderGroupElector private constructor(
    private val hazelcast: HazelcastInstance,
    options: LeaderGroupElectionOptions,
): LeaderGroupElector {

    companion object: KLogging() {
        const val LOCK_MAP_NAME = "bluetape4k:leader:group:locks"

        @JvmStatic
        operator fun invoke(
            hazelcast: HazelcastInstance,
            options: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
        ): HazelcastLeaderGroupElector {
            options.maxLeaders.requirePositiveNumber("maxLeaders")
            return HazelcastLeaderGroupElector(hazelcast, options)
        }
    }

    override val maxLeaders: Int = options.maxLeaders
    private val waitTime = options.waitTime
    private val leaseTime = options.leaseTime
    private val minLeaseTime = options.minLeaseTime

    private val lockMap: IMap<String, String> = hazelcast.getMap(LOCK_MAP_NAME)

    private fun slotKey(lockName: String, slot: Int) = "$lockName:slot:$slot"

    override fun activeCount(lockName: String): Int =
        (0 until maxLeaders).count { slot -> lockMap.containsKey(slotKey(lockName, slot)) }

    override fun availableSlots(lockName: String): Int = maxLeaders - activeCount(lockName)

    override fun state(lockName: String): LeaderGroupState =
        LeaderGroupState(lockName, maxLeaders, activeCount(lockName))

    override fun <T> runIfLeader(lockName: String, action: () -> T): T? {
        lockName.requireNotBlank("lockName")

        val slotWaitTime = waitTime / maxLeaders
        log.debug { "리더 그룹 슬롯 획득을 요청합니다. lockName=$lockName, maxLeaders=$maxLeaders" }

        val acquiredLock = (0 until maxLeaders)
            .asSequence()
            .map { slot -> HazelcastLock(lockMap, slotKey(lockName, slot)) }
            .firstOrNull { lock -> lock.tryLock(slotWaitTime, leaseTime) }

        if (acquiredLock == null) {
            log.debug { "리더 그룹 슬롯 획득 실패 (슬롯 없음). lockName=$lockName" }
            return null
        }

        val acquiredAtNanos = System.nanoTime()
        log.debug { "리더 그룹 슬롯을 획득하여 작업을 수행합니다. lockName=$lockName" }
        try {
            return action()
        } finally {
            if (acquiredLock.isHeldByCurrentInstance()) {
                runCatching { acquiredLock.unlock(minLeaseTime, acquiredAtNanos) }
                    .onSuccess { log.debug { "리더 그룹 슬롯을 반납했습니다. lockName=$lockName" } }
                    .onFailure { e -> log.error(e) { "Fail to release group slot. lockName=$lockName" } }
            }
        }
    }

    override fun <T> runAsyncIfLeader(
        lockName: String,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> {
        lockName.requireNotBlank("lockName")

        val slotWaitTime = waitTime / maxLeaders

        return CompletableFuture.supplyAsync({
            (0 until maxLeaders)
                .asSequence()
                .map { slot -> HazelcastLock(lockMap, slotKey(lockName, slot)) }
                .firstOrNull { lock -> lock.tryLock(slotWaitTime, leaseTime) }
        }, executor).thenComposeAsync({ acquiredLock ->
            if (acquiredLock == null) {
                log.debug { "리더 그룹 슬롯 획득 실패 (비동기). lockName=$lockName" }
                CompletableFuture.completedFuture(null)
            } else {
                val acquiredAtNanos = System.nanoTime()
                log.debug { "리더 그룹 슬롯을 획득하여 비동기 작업을 수행합니다. lockName=$lockName" }
                val actionFuture = runCatching { action() }
                    .getOrElse { error ->
                        runCatching { acquiredLock.unlock(minLeaseTime, acquiredAtNanos) }
                            .onFailure { e -> log.error(e) { "Fail to release group slot on action error (async). lockName=$lockName" } }
                        return@thenComposeAsync CompletableFuture.failedFuture(error)
                    }
                actionFuture.whenCompleteAsync({ _, _ ->
                    if (acquiredLock.isHeldByCurrentInstance()) {
                        runCatching { acquiredLock.unlock(minLeaseTime, acquiredAtNanos) }
                            .onSuccess { log.debug { "비동기 리더 그룹 슬롯을 반납했습니다. lockName=$lockName" } }
                            .onFailure { e -> log.error(e) { "Fail to release group slot (async). lockName=$lockName" } }
                    }
                }, executor)
            }
        }, executor)
    }
}

/**
 * Hazelcast 분산 세마포어(슬롯 기반)를 이용하여 최대 [options.maxLeaders]개의 리더로 선출된 경우에만 [action]을 실행합니다.
 */
inline fun <T> HazelcastInstance.runIfLeaderGroup(
    lockName: String,
    options: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
    crossinline action: () -> T,
): T? {
    lockName.requireNotBlank("lockName")
    return HazelcastLeaderGroupElector(this, options).runIfLeader(lockName) { action() }
}
