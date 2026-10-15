package io.bluetape4k.leader.coroutines

import io.bluetape4k.codec.Base58
import io.bluetape4k.coroutines.flow.extensions.subject.PublishSubject
import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.LeaderElectionEvent
import io.bluetape4k.leader.LeaderElectionEventPublisher
import io.bluetape4k.leader.LeaderElectionListener
import io.bluetape4k.leader.LeaderElectionListenerRegistry
import io.bluetape4k.leader.LeaderElectionListenerSupport
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LeaderGroupState
import io.bluetape4k.leader.LeaderLeaseAutoExtender
import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider
import io.bluetape4k.leader.diagnostics.LocalLeaderBackendDiagnostics
import io.bluetape4k.leader.internal.ExtendDelegate
import io.bluetape4k.leader.local.AbstractLocalLeaderGroupElector
import io.bluetape4k.leader.local.LocalLeaderStateRegistry
import io.bluetape4k.leader.remainingMinLeaseTime
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException

/**
 * JVM 프로세스 안에서 lock별 coroutine `Semaphore`로 그룹 slot을 조정하고 상태와 이벤트를 기록합니다.
 * 이 elector가 제공하는 slot은 다른 프로세스와 공유되지 않습니다.
 * @property options node ID, 허용 slot 수, 대기 시간 및 lease 설정
 */
class LocalSuspendLeaderGroupElector private constructor(
    private val options: LeaderGroupElectionOptions,
): SuspendLeaderGroupElector,
   LeaderElectionListenerRegistry,
   LeaderElectionEventPublisher,
   LeaderBackendDiagnosticsProvider by LocalLeaderBackendDiagnostics {

    companion object: KLoggingChannel() {
        /**
         * 양수의 `maxLeaders`를 가진 local group elector를 생성합니다.
         * @param options node ID, 허용 slot 수, 대기 시간 및 lease 설정
         * @return 전달한 옵션으로 구성한 JVM 내부 그룹 elector
         */
        operator fun invoke(
            options: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
        ): LocalSuspendLeaderGroupElector =
            options
                .also { it.maxLeaders.requirePositiveNumber("maxLeaders") }
                .let(::LocalSuspendLeaderGroupElector)
    }

    private val semaphores = ConcurrentHashMap<String, Semaphore>()
    private val listeners = LeaderElectionListenerSupport()
    private val eventSubject = PublishSubject<LeaderElectionEvent>()
    private val states = LocalLeaderStateRegistry()

    override val events: Flow<LeaderElectionEvent> = eventSubject

    override fun addListener(listener: LeaderElectionListener): AutoCloseable =
        listeners.addListener(listener)

    override fun removeListener(listener: LeaderElectionListener): Boolean =
        listeners.removeListener(listener)

    private fun getSemaphore(lockName: String): Semaphore {
        lockName.requireNotBlank("lockName")
        return semaphores.computeIfAbsent(lockName) { Semaphore(maxLeaders) }
    }

    override val maxLeaders: Int = options.maxLeaders

    /**
     * 해당 JVM semaphore에서 사용 중인 permit 수를 계산합니다.
     * @param lockName 점유 slot 수를 조회할 그룹 lock 이름
     * @return `maxLeaders`에서 현재 남은 permit 수를 뺀 값
     */
    override fun activeCount(lockName: String): Int =
        maxLeaders - getSemaphore(lockName).availablePermits

    /**
     * 해당 JVM semaphore에 남아 있는 permit 수를 반환합니다.
     * @param lockName 획득 가능한 slot 수를 조회할 그룹 lock 이름
     * @return 현재 남은 permit 수
     */
    override fun availableSlots(lockName: String): Int =
        getSemaphore(lockName).availablePermits

    /**
     * 최대 slot 수와 현재 점유 수, 기록된 leader 정보를 담은 snapshot을 반환합니다.
     * @param lockName 상태 snapshot을 조회할 그룹 lock 이름
     * @return 해당 그룹 lock의 상태 snapshot
     */
    override fun state(lockName: String): LeaderGroupState =
        states.groupState(lockName, maxLeaders, activeCount(lockName))

    /**
     * `runIfLeader`는 leadership을 획득한 경우에만 action을 실행하고, 획득하지 못하면 null을 반환합니다.
     *
     * `options.waitTime`은 semaphore permit 획득에만 적용되고 `action` 실행 시간은 제한하지 않습니다.
     * @param lockName 획득할 JVM 그룹 semaphore 이름
     * @param action permit을 보유한 동안 실행할 suspend 작업
     * @return permit을 얻으면 작업 결과, 대기 시간 안에 얻지 못하면 `null`
     */
    override suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T? =
        tryWithPermit(
            lockName = lockName,
            auditLeaderId = options.nodeId,
            nodeId = options.nodeId,
            action = action,
        )

    /**
     * `runIfLeader`는 leadership을 획득한 경우에만 action을 실행하고, 획득하지 못하면 null을 반환합니다.
     *
     * `slot.leaderId`를 상태와 이벤트에 기록합니다. 대기 시간은 semaphore 획득에만 적용됩니다.
     * @param slot 그룹 lock 이름과 audit leader ID를 담은 slot
     * @param action permit을 보유한 동안 실행할 suspend 작업
     * @return permit을 얻으면 작업 결과, 대기 시간 안에 얻지 못하면 `null`
     */
    override suspend fun <T> runIfLeader(slot: LeaderSlot, action: suspend () -> T): T? =
        tryWithPermit(
            lockName = slot.lockName,
            auditLeaderId = slot.leaderId,
            nodeId = options.nodeId,
            action = action,
        )

    /**
     * action에서 발생한 `Exception`은 `ActionFailed`로 반환하고 coroutine 취소는 전파합니다.
     * @param slot 그룹 lock 이름과 결과에 포함할 leader ID를 담은 slot
     * @param action permit을 보유한 동안 실행할 suspend 작업
     * @return 획득 결과, contention skip, action 실패를 구분하는 `LeaderRunResult`
     */
    override suspend fun <T> runIfLeaderResultSuspend(
        slot: LeaderSlot,
        action: suspend () -> T,
    ): LeaderRunResult<T> {
        var elected = false
        val value = try {
            tryWithPermit(
                lockName = slot.lockName,
                auditLeaderId = slot.leaderId,
                nodeId = options.nodeId,
            ) {
                elected = true
                action()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (elected) {
                return LeaderRunResult.ActionFailed(e)
            }
            throw e
        }
        return if (elected) LeaderRunResult.Elected(value, leaderId = slot.leaderId) else LeaderRunResult.Skipped
    }

    private suspend fun <T> tryWithPermit(
        lockName: String,
        auditLeaderId: String,
        nodeId: String? = options.nodeId,
        action: suspend () -> T,
    ): T? {
        val semaphore = getSemaphore(lockName)
        // withTimeoutOrNull 은 semaphore 획득 시도에만 적용합니다. action() 실행은 포함하지 않습니다.
        val acquired = withTimeoutOrNull(options.waitTime) {
            semaphore.acquire()
            true
        } ?: run {
            listeners.notifySkipped(lockName)
            eventSubject.emit(LeaderElectionEvent.Skipped(lockName))
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
        val slot = requireNotNull(lease.slot) {
            "Group lease.slot must be non-null for lockName=$lockName, kind=GROUP"
        }

        val identity = LockIdentity(
            lockName = lockName,
            kind = LockIdentity.AnnotationKind.GROUP,
            factoryBeanName = AbstractLocalLeaderGroupElector.LOCAL_GROUP_FACTORY_BEAN_NAME,
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
        eventSubject.emit(LeaderElectionEvent.Elected.fromLease(lockName, lease))

        return try {
            withContext(LockHandleElement(handle)) {
                action()
            }
        } finally {
            withContext(NonCancellable) {
                LeaderLeaseAutoExtender.closeSuspend(watchdog)
                delayRemainingMinLeaseTime(startedAtNanos)
                states.releaseGroup(lockName, lease)
                if (acquired) semaphore.release()
                listeners.notifyRevoked(lockName)
                eventSubject.emit(LeaderElectionEvent.Revoked(lockName))
            }
        }
    }

    private suspend fun delayRemainingMinLeaseTime(startedAtNanos: Long) {
        val remaining = remainingMinLeaseTime(startedAtNanos, options.minLeaseTime)
        if (remaining > kotlin.time.Duration.ZERO) {
            delay(remaining)
        }
    }
}
