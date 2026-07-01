package io.bluetape4k.leader.hazelcast

import com.hazelcast.core.HazelcastInstance
import com.hazelcast.map.IMap
import io.bluetape4k.leader.AopScopeAccess
import io.bluetape4k.leader.LeaderGroupElector
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LeaderGroupState
import io.bluetape4k.leader.LeaderLeaseAutoExtender
import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.hazelcast.internal.HazelcastBackendErrorClassifier
import io.bluetape4k.leader.hazelcast.internal.HazelcastSlotExtendDelegate
import io.bluetape4k.leader.hazelcast.lock.HazelcastLock
import io.bluetape4k.leader.internal.CompositeBackendErrorClassifier
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.error
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/**
 * Multi-leader election implementation using [IMap] slot-based distributed semaphore.
 *
 * Simulates `maxLeaders` slots using N [HazelcastLock] instances with keys `${lockName}:slot:N`.
 * Works without the CP Subsystem and is token-based, so it is not bound to any thread.
 *
 * ## ExtendDelegate Integration (T12 PR 7 / Issue #79)
 *
 * - Wraps the acquired per-slot [HazelcastLock] with [HazelcastSlotExtendDelegate], sharing the same reference with the watchdog (AC-15).
 * - When the aspect calls `LockExtender.extendActiveLock`, the extend is executed through the same delegate with R6 (IMap auto-evict) applied.
 * - sync group: pushed to both `withPushedSync(handle)` + `setCapture(handle)`.
 *
 * ```kotlin
 * val election = HazelcastLeaderGroupElector(hazelcastInstance, LeaderGroupElectionOptions(maxLeaders = 3))
 * val result = election.runIfLeader("batch-job") { processChunk() }
 * ```
 *
 * @param hazelcast Hazelcast client instance
 * @param options Leader group election options (maxLeaders, waitTime, leaseTime)
 */
class HazelcastLeaderGroupElector private constructor(
    private val hazelcast: HazelcastInstance,
    options: LeaderGroupElectionOptions,
): LeaderGroupElector {

    companion object: KLogging() {
        const val LOCK_MAP_NAME = "bluetape4k:leader:group:locks"
        internal const val HAZELCAST_GROUP_FACTORY_BEAN_NAME = "hazelcast-leader-group-elector"
        internal val ERROR_CLASSIFIER = CompositeBackendErrorClassifier(HazelcastBackendErrorClassifier)

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

        var acquiredLock: HazelcastLock? = null
        var acquiredSlot = -1
        var acquiredSlotKey: String? = null

        for (slot in 0 until maxLeaders) {
            val slotKeyValue = slotKey(lockName, slot)
            val lock = HazelcastLock(lockMap, slotKeyValue, LOCK_MAP_NAME, hazelcast::newTransactionContext)
            if (lock.tryLock(slotWaitTime, leaseTime)) {
                acquiredLock = lock
                acquiredSlot = slot
                acquiredSlotKey = slotKeyValue
                break
            }
        }

        if (acquiredLock == null || acquiredSlotKey == null) {
            log.debug { "리더 그룹 슬롯 획득 실패 (슬롯 없음). lockName=$lockName" }
            return null
        }

        val lock = acquiredLock
        val slot = acquiredSlot
        val slotKeyValue = acquiredSlotKey
        val acquiredAtNanos = System.nanoTime()
        log.debug { "리더 그룹 슬롯을 획득하여 작업을 수행합니다. lockName=$lockName, slot=$slot" }

        val delegate = HazelcastSlotExtendDelegate(lock)
        val identity = LockIdentity(
            lockName = lockName,
            kind = LockIdentity.AnnotationKind.GROUP,
            factoryBeanName = HAZELCAST_GROUP_FACTORY_BEAN_NAME,
            groupParams = LockIdentity.GroupParams(maxLeaders),
        )
        val handle = LeaderLockHandle.real(
            identity = identity,
            token = slotKeyValue,
            acquiredAtNanos = acquiredAtNanos,
            slotId = slot.toString(),
            extendDelegate = delegate,
        )
        // Group elector: autoExtend 옵션 부재 — caller 가 LockExtender 로 명시적 연장. watchdog disabled.
        val watchdog = LeaderLeaseAutoExtender.start(false, leaseTime, delegate, ERROR_CLASSIFIER)

        try {
            return AopScopeAccess.withPushedSync(handle) {
                AopScopeAccess.setCapture(handle)
                try {
                    action()
                } finally {
                    AopScopeAccess.clearCapture()
                }
            }
        } finally {
            watchdog.close()
            runCatching { lock.unlock(minLeaseTime, acquiredAtNanos) }
                .onSuccess { log.debug { "리더 그룹 슬롯을 반납했습니다. lockName=$lockName, slot=$slot" } }
                .onFailure { e -> log.error(e) { "Fail to release group slot. lockName=$lockName, slot=$slot" } }
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
                .map { slot ->
                    HazelcastLock(lockMap, slotKey(lockName, slot), LOCK_MAP_NAME, hazelcast::newTransactionContext) to slot
                }
                .firstOrNull { (lock, _) -> lock.tryLock(slotWaitTime, leaseTime) }
        }, executor).thenComposeAsync({ acquired ->
            if (acquired == null) {
                log.debug { "리더 그룹 슬롯 획득 실패 (비동기). lockName=$lockName" }
                CompletableFuture.completedFuture(null)
            } else {
                val (lock, slot) = acquired
                val acquiredAtNanos = System.nanoTime()
                log.debug { "리더 그룹 슬롯을 획득하여 비동기 작업을 수행합니다. lockName=$lockName, slot=$slot" }
                val delegate = HazelcastSlotExtendDelegate(lock)
                // Group elector: watchdog disabled (autoExtend 옵션 부재)
                val watchdog = LeaderLeaseAutoExtender.start(false, leaseTime, delegate, ERROR_CLASSIFIER)
                // async path 는 handle push 미수행 (AOP scope sync/suspend 만 지원)
                val actionFuture = runCatching { action() }
                    .getOrElse { e ->
                        watchdog.close()
                        runCatching { lock.unlock(minLeaseTime, acquiredAtNanos) }
                            .onFailure { ex -> log.error(ex) { "Fail to release group slot on action error (async). lockName=$lockName, slot=$slot" } }
                        return@thenComposeAsync CompletableFuture.failedFuture(e)
                    }
                actionFuture.whenCompleteAsync({ _, _ ->
                    watchdog.close()
                    runCatching { lock.unlock(minLeaseTime, acquiredAtNanos) }
                        .onSuccess { log.debug { "비동기 리더 그룹 슬롯을 반납했습니다. lockName=$lockName, slot=$slot" } }
                        .onFailure { e -> log.error(e) { "Fail to release group slot (async). lockName=$lockName, slot=$slot" } }
                }, executor)
            }
        }, executor)
    }
}

/**
 * Executes [action] only when elected as one of up to [options.maxLeaders] leaders using a Hazelcast distributed semaphore (slot-based).
 */
inline fun <T> HazelcastInstance.runIfLeaderGroup(
    lockName: String,
    options: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
    crossinline action: () -> T,
): T? {
    lockName.requireNotBlank("lockName")
    return HazelcastLeaderGroupElector(this, options).runIfLeader(lockName) { action() }
}
