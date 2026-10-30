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
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider
import io.bluetape4k.leader.diagnostics.LocalLeaderBackendDiagnostics
import io.bluetape4k.leader.internal.CaptureScope
import io.bluetape4k.leader.internal.ExtendDelegate
import io.bluetape4k.leader.internal.LockStateHolder
import io.bluetape4k.leader.parkRemainingMinLeaseTime
import io.bluetape4k.logging.KotlinLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireNotNull
import io.bluetape4k.support.requirePositiveNumber
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

private val log = KotlinLogging.logger {}

/**
 * JVM 프로세스 안에서 lock별 fair `Semaphore`, 그룹 상태, listener를 관리하는 그룹 선거 기반 클래스입니다.
 * 이 구현이 제공하는 slot은 다른 프로세스와 공유되지 않습니다.
 * @property options node ID, 허용 slot 수, 대기 시간 및 lease 설정
 */
abstract class AbstractLocalLeaderGroupElector(
    protected val options: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
): LeaderGroupElectionState,
   LeaderElectionListenerRegistry,
   LeaderBackendDiagnosticsProvider by LocalLeaderBackendDiagnostics {

    companion object {
        /**
         * `LOCAL_GROUP_FACTORY_BEAN_NAME`는 backend별 leader elector 인스턴스를 생성하는 factory 계약입니다.
         */
        internal const val LOCAL_GROUP_FACTORY_BEAN_NAME = "local-leader-group-elector"
    }

    init {
        options.maxLeaders.requirePositiveNumber("options.maxLeaders")
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
     * `lockName`에 대응하는 fair [Semaphore]를 반환하고, 없으면 `maxLeaders` 크기로 생성합니다.
     *
     * @param lockName group leader election에 사용할 lock 이름입니다. blank 값은 허용하지 않습니다.
     * @return 지정한 lock 이름에 재사용되는 [Semaphore] 인스턴스입니다.
     */
    protected fun getSemaphore(lockName: String): Semaphore {
        lockName.requireNotBlank("lockName")
        return semaphores.computeIfAbsent(lockName) {
            Semaphore(maxLeaders, true)
        }
    }

    /**
     * 해당 이름의 semaphore permit을 얻을 때까지 대기한 뒤 작업을 실행하고, 작업이 끝나면 permit을 돌려줍니다.
     * @param lockName JVM 안에서 사용할 그룹 lock 이름
     * @param action permit을 보유한 동안 실행할 작업
     * @return `action`이 반환한 값
     */
    protected inline fun <T> withPermit(lockName: String, action: () -> T): T {
        val semaphore = getSemaphore(lockName)
        semaphore.acquire()
        try {
            return action()
        } finally {
            semaphore.release()
        }
    }

    /**
     * `options.waitTime` 안에 permit을 얻으면 작업을 실행하고 반환합니다. 시간 내에 얻지 못하면 작업을 실행하지 않고 `null`을 반환합니다.
     * @param lockName JVM 안에서 사용할 그룹 lock 이름
     * @param action permit을 보유한 동안 실행할 작업
     * @return permit을 얻은 경우 `action`의 결과, 시간 초과로 얻지 못한 경우 `null`
     */
    protected fun <T> tryWithPermit(lockName: String, action: () -> T): T? =
        tryWithPermit(
            lockName = lockName,
            auditLeaderId = options.nodeId,
            nodeId = options.nodeId,
            action = action,
        )

    /**
     * 제한 시간 안에 permit을 얻으면 지정 identity로 그룹 lease와 listener 이벤트를 기록하고 작업을 실행한 뒤 모두 정리합니다.
     * @param lockName JVM 안에서 사용할 그룹 lock 이름
     * @param auditLeaderId 상태 기록 및 listener 이벤트에 사용할 leader ID
     * @param nodeId 상태 snapshot에 기록할 노드 ID. 생략하면 `options.nodeId`를 사용합니다.
     * @param action permit을 보유한 동안 실행할 작업
     * @return permit을 얻은 경우 `action`의 결과, 시간 초과로 얻지 못한 경우 `null`
     */
    protected fun <T> tryWithPermit(
        lockName: String,
        auditLeaderId: String,
        nodeId: String? = options.nodeId,
        action: () -> T,
    ): T? {
        log.debug { "tryWithPermit. lockName=$lockName auditLeaderId=$auditLeaderId" }

        val semaphore = getSemaphore(lockName)
        val acquired = semaphore.tryAcquire(options.waitTime.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        if (!acquired) {
            listeners.notifySkipped(lockName)
            return null
        }
        val startedAtNanos = System.nanoTime()
        val token = Base58.randomString(8)
        val lease = states.acquireGroup(
            lockName,
            auditLeaderId = auditLeaderId,
            nodeId = nodeId,
            leaseTime = options.leaseTime,
            maxLeaders = maxLeaders,
        )
        val slot = lease.slot.requireNotNull {
            "Group lease.slot must be non-null for lockName=$lockName, kind=GROUP"
        }

        val identity = LockIdentity(
            lockName = lockName,
            kind = LockIdentity.AnnotationKind.GROUP,
            factoryBeanName = LOCAL_GROUP_FACTORY_BEAN_NAME,
            groupParams = LockIdentity.GroupParams(maxLeaders),
        )
        val lastExtendDeadlineRef = AtomicReference(Instant.EPOCH)

        val delegate = object: ExtendDelegate {
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
            auditLeaderId = auditLeaderId,
        )
        val watchdog = LeaderLeaseAutoExtender.start(false, options.leaseTime, delegate)
        listeners.notifyElected(lockName, lease)

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
     * 해당 JVM semaphore에서 사용 중인 permit 수를 계산합니다.
     * @param lockName 점유 slot 수를 조회할 그룹 lock 이름
     * @return `maxLeaders`에서 현재 남은 permit 수를 뺀 값
     */
    override fun activeCount(lockName: String): Int =
        maxLeaders - getSemaphore(lockName).availablePermits()

    /**
     * 해당 JVM semaphore에 남아 있는 permit 수를 반환합니다.
     * @param lockName 사용 가능한 slot 수를 조회할 그룹 lock 이름
     * @return 현재 획득 가능한 permit 수
     */
    override fun availableSlots(lockName: String): Int =
        getSemaphore(lockName).availablePermits()

    /**
     * 최대 slot 수와 현재 점유 수, backend가 기록한 leader 정보를 담은 snapshot을 반환합니다.
     * @param lockName 상태 snapshot을 조회할 그룹 lock 이름
     * @return 해당 그룹 lock의 상태 snapshot
     */
    override fun state(lockName: String): LeaderGroupState =
        states.groupState(lockName, maxLeaders, activeCount(lockName))
}
