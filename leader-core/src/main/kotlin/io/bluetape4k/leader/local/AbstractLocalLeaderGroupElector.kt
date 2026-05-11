package io.bluetape4k.leader.local

import io.bluetape4k.codec.Base58
import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.LeaderElectionListener
import io.bluetape4k.leader.LeaderElectionListenerRegistry
import io.bluetape4k.leader.LeaderElectionListenerSupport
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LeaderGroupElectionState
import io.bluetape4k.leader.LeaderGroupState
import io.bluetape4k.leader.LeaderLeaseAutoExtender
import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.internal.CaptureScope
import io.bluetape4k.leader.internal.ExtendDelegate
import io.bluetape4k.leader.internal.LockStateHolder
import io.bluetape4k.leader.parkRemainingMinLeaseTime
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 로컬(단일 JVM) 리더 그룹 선출 구현체들의 공통 상태 관리를 제공하는 추상 클래스입니다.
 *
 * ## 역할
 * - `lockName`별 [Semaphore] 풀을 [ConcurrentHashMap]으로 관리합니다.
 * - [LeaderGroupElectionState]의 상태 조회 메서드([activeCount], [availableSlots], [state])를 구현합니다.
 * - 하위 클래스는 [getSemaphore]를 이용해 슬롯을 획득/반환하고 실행 로직을 구현합니다.
 *
 * ## 하위 클래스
 * - [LocalLeaderGroupElector]: 동기 + 비동기([java.util.concurrent.CompletableFuture]) 실행
 * - [LocalAsyncLeaderGroupElector]: 비동기([java.util.concurrent.CompletableFuture]) 실행만
 * - [LocalVirtualThreadLeaderGroupElector]: [io.bluetape4k.concurrent.virtualthread.VirtualFuture] 실행
 *
 * @param options 리더 그룹 선출 옵션 (maxLeaders, waitTime, leaseTime). 기본값은 [LeaderGroupElectionOptions.Default]
 */
abstract class AbstractLocalLeaderGroupElector(
    protected val options: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
): LeaderGroupElectionState, LeaderElectionListenerRegistry {

    companion object {
        /** [LockIdentity.factoryBeanName] 진단 metadata 용 상수 — Local backend group. */
        internal const val LOCAL_GROUP_FACTORY_BEAN_NAME = "local-leader-group-elector"
    }

    init {
        options.maxLeaders.requirePositiveNumber("maxLeaders")
    }

    override val maxLeaders: Int = options.maxLeaders

    private val semaphores = ConcurrentHashMap<String, Semaphore>()
    private val listeners = LeaderElectionListenerSupport()
    private val states = LocalLeaderStateRegistry()

    override fun addListener(listener: LeaderElectionListener): AutoCloseable =
        listeners.addListener(listener)

    override fun removeListener(listener: LeaderElectionListener): Boolean =
        listeners.removeListener(listener)

    /**
     * [lockName]에 대한 [Semaphore]를 반환합니다. 없으면 `Semaphore(maxLeaders, fair=true)`를 생성합니다.
     *
     * ```kotlin
     * val semaphore = getSemaphore("batch-job")
     * semaphore.acquire()
     * try { /* 임계 영역 */ } finally { semaphore.release() }
     * ```
     *
     * @param lockName 락 이름 (blank 불가)
     * @return 해당 lockName에 대한 [Semaphore] 인스턴스
     */
    protected fun getSemaphore(lockName: String): Semaphore {
        lockName.requireNotBlank("lockName")
        return semaphores.computeIfAbsent(lockName) { Semaphore(maxLeaders, true) }
    }

    /**
     * [lockName]의 슬롯을 획득한 상태에서 [action]을 실행하고, 완료 시 슬롯을 반환합니다.
     *
     * ```kotlin
     * val result = withPermit("batch-job") { "done" }
     * // result == "done"
     * ```
     *
     * @param lockName 락 이름
     * @param action 슬롯을 획득한 상태에서 실행할 작업
     * @return [action] 실행 결과
     */
    protected fun <T> withPermit(lockName: String, action: () -> T): T {
        val semaphore = getSemaphore(lockName)
        semaphore.acquire()
        try {
            return action()
        } finally {
            semaphore.release()
        }
    }

    /**
     * [lockName]의 슬롯을 [waitTime] 내에 획득하면 [action]을 실행하고, 실패하면 `null`을 반환합니다.
     *
     * ```kotlin
     * val result = tryWithPermit("batch-job") { "done" }
     * // result == "done" (획득 성공) 또는 null (획득 실패)
     * ```
     *
     * @param lockName 락 이름
     * @param action 슬롯 획득 성공 시 실행할 작업
     * @return [action] 실행 결과, 획득 실패 시 `null`
     */
    protected fun <T> tryWithPermit(lockName: String, action: () -> T): T? {
        val semaphore = getSemaphore(lockName)
        val acquired = semaphore.tryAcquire(options.waitTime.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        if (!acquired) {
            listeners.notifySkipped(lockName)
            return null
        }
        val startedAtNanos = System.nanoTime()
        val token = Base58.randomString(8)
        val lease = states.acquireGroup(lockName, options.nodeId, options.leaseTime, maxLeaders)
        val slot = requireNotNull(lease.slot) {
            "Group lease.slot must be non-null for lockName=$lockName, kind=GROUP"
        }

        val identity = LockIdentity(
            lockName = lockName,
            kind = LockIdentity.AnnotationKind.GROUP,
            factoryBeanName = LOCAL_GROUP_FACTORY_BEAN_NAME,
            groupParams = LockIdentity.GroupParams(maxLeaders),
        )
        val lastExtendDeadlineRef = AtomicReference(Instant.EPOCH)
        val delegate = object : ExtendDelegate {
            private val _lastExtendDeadline = lastExtendDeadlineRef
            override val lastExtendDeadline: AtomicReference<Instant> get() = _lastExtendDeadline
            override fun extend(lockAtMostFor: kotlin.time.Duration): ExtendOutcome {
                val extended = states.extendGroup(lockName, slot, lockAtMostFor)
                return if (extended) {
                    ExtendOutcome.Extended(Instant.now().plusMillis(lockAtMostFor.inWholeMilliseconds))
                } else {
                    ExtendOutcome.NotHeld
                }
            }
            override fun isHeld(): Boolean = states.isSlotHeld(lockName, slot)
        }

        val handle = LeaderLockHandle.real(
            identity = identity,
            token = token,
            acquiredAtNanos = startedAtNanos,
            slotId = slot.toString(),
            extendDelegate = delegate,
        )
        val watchdog = LeaderLeaseAutoExtender.start(false, options.leaseTime, delegate)
        listeners.notifyElected(lockName)
        return try {
            LockStateHolder.withPushed(handle) {
                CaptureScope.runWithCapture(handle) {
                    action()
                }
            }
        } finally {
            watchdog.close()
            parkRemainingMinLeaseTime(startedAtNanos, options.minLeaseTime)
            states.releaseGroup(lockName, lease)
            semaphore.release()
            listeners.notifyRevoked(lockName)
        }
    }

    /**
     * [lockName]에 대해 현재 활성(실행 중인) 리더 수를 반환합니다.
     *
     * ```kotlin
     * val count = activeCount("batch-job")
     * // count == 0  (아무도 실행 중이 아닐 때)
     * ```
     *
     * @param lockName 조회할 락 이름
     * @return 현재 활성 리더 수 (근사값)
     */
    override fun activeCount(lockName: String): Int =
        maxLeaders - getSemaphore(lockName).availablePermits()

    /**
     * [lockName]에 대해 새 리더를 수용할 수 있는 남은 슬롯 수를 반환합니다.
     *
     * ```kotlin
     * val slots = availableSlots("batch-job")
     * // slots == maxLeaders  (아무도 실행 중이 아닐 때)
     * ```
     *
     * @param lockName 조회할 락 이름
     * @return 사용 가능한 슬롯 수 (근사값)
     */
    override fun availableSlots(lockName: String): Int =
        getSemaphore(lockName).availablePermits()

    /**
     * [lockName]에 대한 현재 [LeaderGroupState] 스냅샷을 반환합니다.
     *
     * ```kotlin
     * val state = state("batch-job")
     * // state.maxLeaders == maxLeaders
     * // state.activeCount == 0
     * // state.isEmpty == true
     * ```
     *
     * @param lockName 조회할 락 이름
     * @return 현재 리더 그룹 상태 스냅샷
     */
    override fun state(lockName: String): LeaderGroupState =
        states.groupState(lockName, maxLeaders, activeCount(lockName))
}
