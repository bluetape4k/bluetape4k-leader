package io.bluetape4k.leader.hazelcast

import com.hazelcast.core.HazelcastInstance
import com.hazelcast.map.IMap
import io.bluetape4k.leader.AopScopeAccess
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LeaderGroupState
import io.bluetape4k.leader.LeaderLeaseAutoExtender
import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElector
import io.bluetape4k.leader.hazelcast.internal.HazelcastBackendErrorClassifier
import io.bluetape4k.leader.hazelcast.internal.HazelcastSuspendSlotExtendDelegate
import io.bluetape4k.leader.hazelcast.lock.HazelcastSuspendLock
import io.bluetape4k.leader.internal.CompositeBackendErrorClassifier
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * [IMap] 슬롯 기반 분산 세마포어를 이용한 코루틴 기반 복수 리더 선출 구현체입니다.
 *
 * ## ExtendDelegate 통합 (T12 PR 7 / Issue #79)
 *
 * - acquire 된 per-slot [HazelcastSuspendLock] 을 [HazelcastSuspendSlotExtendDelegate] 로 wrap 하여 watchdog 와 동일 reference 공유 (AC-15).
 * - aspect 의 `LockExtenderSuspend.extendActiveLockSuspend` 는 동일 delegate reference 를 사용합니다.
 * - suspend group: `setCapture(handle)` + `withContext(createLockHandleElement(handle))` 양쪽에 push.
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
        internal const val HAZELCAST_SUSPEND_GROUP_FACTORY_BEAN_NAME = "hazelcast-suspend-leader-group-elector"
        internal val ERROR_CLASSIFIER = CompositeBackendErrorClassifier(HazelcastBackendErrorClassifier)

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
    private val minLeaseTime = options.minLeaseTime

    private val lockMap: IMap<String, String> = hazelcast.getMap(HazelcastLeaderGroupElector.LOCK_MAP_NAME)

    private fun slotKey(lockName: String, slot: Int) = "$lockName:slot:$slot"

    override fun activeCount(lockName: String): Int =
        (0 until maxLeaders).count { slot -> lockMap.containsKey(slotKey(lockName, slot)) }

    override fun availableSlots(lockName: String): Int = maxLeaders - activeCount(lockName)

    override fun state(lockName: String): LeaderGroupState =
        LeaderGroupState(lockName, maxLeaders, activeCount(lockName))

    override suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T? {
        lockName.requireNotBlank("lockName")

        val slotWaitTime = waitTime / maxLeaders
        log.debug { "리더 그룹 슬롯 획득을 요청합니다 (suspend). lockName=$lockName, maxLeaders=$maxLeaders" }

        var acquiredLock: HazelcastSuspendLock? = null
        var acquiredSlot = -1
        var acquiredSlotKey: String? = null

        for (slot in 0 until maxLeaders) {
            currentCoroutineContext().ensureActive()
            val slotKeyValue = slotKey(lockName, slot)
            val lock = HazelcastSuspendLock(lockMap, slotKeyValue)
            if (lock.tryLock(slotWaitTime, leaseTime)) {
                acquiredLock = lock
                acquiredSlot = slot
                acquiredSlotKey = slotKeyValue
                break
            }
        }

        if (acquiredLock == null || acquiredSlotKey == null) {
            log.debug { "리더 그룹 슬롯 획득 실패 (슬롯 없음, suspend). lockName=$lockName" }
            return null
        }

        val lock = acquiredLock
        val slot = acquiredSlot
        val slotKeyValue = acquiredSlotKey
        val acquiredAtNanos = System.nanoTime()
        log.debug { "리더 그룹 슬롯을 획득하여 suspend 작업을 수행합니다. lockName=$lockName, slot=$slot" }

        val delegate = HazelcastSuspendSlotExtendDelegate(lock)
        val identity = LockIdentity(
            lockName = lockName,
            kind = LockIdentity.AnnotationKind.GROUP,
            factoryBeanName = HAZELCAST_SUSPEND_GROUP_FACTORY_BEAN_NAME,
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
            AopScopeAccess.setCapture(handle)
            try {
                return withContext(AopScopeAccess.createLockHandleElement(handle)) {
                    action()
                }
            } finally {
                AopScopeAccess.clearCapture()
            }
        } finally {
            // NonCancellable: 코루틴 취소 시에도 watchdog close + release 가 중단되지 않도록 보호
            withContext(NonCancellable) {
                watchdog.close()
                if (lock.isHeldByCurrentInstance()) {
                    try {
                        lock.unlock(minLeaseTime, acquiredAtNanos)
                        log.debug { "리더 그룹 슬롯을 반납했습니다 (suspend). lockName=$lockName, slot=$slot" }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log.warn(e) { "그룹 슬롯 해제 실패 (suspend). lockName=$lockName, slot=$slot" }
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
