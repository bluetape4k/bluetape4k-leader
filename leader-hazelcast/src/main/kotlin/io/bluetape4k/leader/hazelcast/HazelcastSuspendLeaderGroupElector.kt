package io.bluetape4k.leader.hazelcast

import com.hazelcast.core.HazelcastInstance
import com.hazelcast.map.IMap
import io.bluetape4k.leader.AopScopeAccess
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LeaderGroupState
import io.bluetape4k.leader.LeaderLeaseAutoExtender
import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElector
import io.bluetape4k.leader.hazelcast.internal.HazelcastBackendErrorClassifier
import io.bluetape4k.leader.hazelcast.internal.HazelcastSuspendSlotExtendDelegate
import io.bluetape4k.leader.hazelcast.lock.HazelcastSuspendLock
import io.bluetape4k.leader.internal.CompositeBackendErrorClassifier
import io.bluetape4k.leader.internal.SuspendExtendDelegate
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
 * `HazelcastSuspendLeaderGroupElector`는 Hazelcast backend의 leader election, lock lease, ownership 확인을 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @property hazelcast Hazelcast backend 호출과 상태 계산에 사용하는 속성입니다.
 */
class HazelcastSuspendLeaderGroupElector private constructor(
    private val hazelcast: HazelcastInstance,
    options: LeaderGroupElectionOptions,
) : SuspendLeaderGroupElector,
    LeaderBackendDiagnosticsProvider by HazelcastLeaderBackendDiagnostics(hazelcast) {

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
            val lock = HazelcastSuspendLock(
                lockMap = lockMap,
                lockKey = slotKeyValue,
                transactionMapName = HazelcastLeaderGroupElector.LOCK_MAP_NAME,
                transactionContextProvider = hazelcast::newTransactionContext,
            )
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

        val delegate: SuspendExtendDelegate = HazelcastSuspendSlotExtendDelegate(lock)
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
            return withContext(AopScopeAccess.createLockHandleElement(handle)) {
                action()
            }
        } finally {
            // NonCancellable: 코루틴 취소 시에도 watchdog close + release 가 중단되지 않도록 보호
            withContext(NonCancellable) {
                watchdog.close()
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

/**
 * `선언` 호출은 Hazelcast backend leader election 계약의 일부 동작을 수행합니다.
 *
 * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
 */
suspend inline fun <T> HazelcastInstance.suspendRunIfLeaderGroup(
    lockName: String,
    options: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
    crossinline action: suspend () -> T,
): T? {
    lockName.requireNotBlank("lockName")
    return HazelcastSuspendLeaderGroupElector(this, options).runIfLeader(lockName) { action() }
}
