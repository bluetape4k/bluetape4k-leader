package io.bluetape4k.leader.coroutines

import io.bluetape4k.codec.Base58
import io.bluetape4k.coroutines.flow.extensions.subject.PublishSubject
import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.LeaderElectionEvent
import io.bluetape4k.leader.LeaderElectionEventPublisher
import io.bluetape4k.leader.LeaderElectionListener
import io.bluetape4k.leader.LeaderElectionListenerRegistry
import io.bluetape4k.leader.LeaderElectionListenerSupport
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderLeaseAutoExtender
import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.LeaderState
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider
import io.bluetape4k.leader.diagnostics.LocalLeaderBackendDiagnostics
import io.bluetape4k.leader.internal.ExtendDelegate
import io.bluetape4k.leader.internal.LocalRequestLeaseStore
import io.bluetape4k.leader.local.AbstractLocalLeaderElector
import io.bluetape4k.leader.local.LocalLeaderStateRegistry
import io.bluetape4k.leader.remainingMinLeaseTime
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.support.requireNotBlank
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException

/**
 * JVM 프로세스 안에서 lock별 coroutine `Mutex`로 단일 leader 작업을 조정하고 상태와 이벤트를 기록합니다.
 * 이 elector의 lock은 다른 프로세스와 공유되지 않습니다.
 * @property options node ID, 대기 시간, lease, 최소 유지 시간 및 자동 연장 설정
 */
class LocalSuspendLeaderElector(
    private val options: LeaderElectionOptions = LeaderElectionOptions.Default,
): SuspendLeaderElector,
   SuspendLeaderLeaseAcquirer,
   LeaderElectionListenerRegistry,
   LeaderElectionEventPublisher,
   LeaderBackendDiagnosticsProvider by LocalLeaderBackendDiagnostics {

    companion object: KLoggingChannel()

    private val mutexes = ConcurrentHashMap<String, Mutex>()
    private val listeners = LeaderElectionListenerSupport()
    private val eventSubject = PublishSubject<LeaderElectionEvent>()
    private val states = LocalLeaderStateRegistry()
    private val requestLeases = LocalRequestLeaseStore()

    override val configuredOptions: LeaderElectionOptions
        get() = options

    override val events: Flow<LeaderElectionEvent> = eventSubject

    override fun addListener(listener: LeaderElectionListener): AutoCloseable =
        listeners.addListener(listener)

    override fun removeListener(listener: LeaderElectionListener): Boolean =
        listeners.removeListener(listener)

    private fun getMutex(lockName: String): Mutex {
        lockName.requireNotBlank("lockName")
        return mutexes.computeIfAbsent(lockName) { Mutex() }
    }

    /**
     * `runIfLeader`는 leadership을 획득한 경우에만 action을 실행하고, 획득하지 못하면 null을 반환합니다.
     *
     * `options.waitTime`은 mutex 획득에만 적용되고 `action` 실행 시간은 제한하지 않습니다.
     * @param lockName 획득할 JVM mutex 이름
     * @param action mutex를 보유한 동안 실행할 suspend 작업
     * @return mutex를 얻으면 작업 결과, 대기 시간 안에 얻지 못하면 `null`
     */
    override suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T? =
        tryWithLock(
            lockName = lockName,
            auditLeaderId = options.nodeId,
            nodeId = options.nodeId,
            action = action,
        )

    /**
     * `runIfLeader`는 leadership을 획득한 경우에만 action을 실행하고, 획득하지 못하면 null을 반환합니다.
     *
     * `slot.leaderId`를 상태와 이벤트에 기록합니다. `options.waitTime`은 mutex 획득에만 적용됩니다.
     * @param slot lock 이름과 audit leader ID를 담은 slot
     * @param action mutex를 보유한 동안 실행할 suspend 작업
     * @return mutex를 얻으면 작업 결과, 대기 시간 안에 얻지 못하면 `null`
     */
    override suspend fun <T> runIfLeader(slot: LeaderSlot, action: suspend () -> T): T? =
        tryWithLock(
            lockName = slot.lockName,
            auditLeaderId = slot.leaderId,
            nodeId = options.nodeId,
            action = action,
        )

    /**
     * action에서 발생한 `Exception`은 `ActionFailed`로 반환하고 coroutine 취소는 전파합니다.
     * @param slot lock 이름과 결과에 포함할 leader ID를 담은 slot
     * @param action mutex를 보유한 동안 실행할 suspend 작업
     * @return 획득 결과, contention skip, action 실패를 구분하는 `LeaderRunResult`
     */
    override suspend fun <T> runIfLeaderResultSuspend(
        slot: LeaderSlot,
        action: suspend () -> T,
    ): LeaderRunResult<T> {
        var elected = false
        val value = try {
            tryWithLock(
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

    private suspend fun <T> tryWithLock(
        lockName: String,
        auditLeaderId: String,
        nodeId: String? = options.nodeId,
        action: suspend () -> T,
    ): T? {
        val mutex = getMutex(lockName)
        // withTimeoutOrNull 은 lock 획득 시도에만 적용합니다. action() 실행은 포함하지 않습니다.
        val acquired = withTimeoutOrNull(options.waitTime) {
            mutex.lock()
            true
        } ?: run {
            listeners.notifySkipped(lockName)
            eventSubject.emit(LeaderElectionEvent.Skipped(lockName))
            return null
        }
        val startedAtNanos = System.nanoTime()
        val token = Base58.randomString(8)
        val lease = states.acquireSingle(
            lockName,
            auditLeaderId = auditLeaderId,
            nodeId = nodeId,
            leaseTime = options.leaseTime
        )

        val identity = LockIdentity(
            lockName = lockName,
            kind = LockIdentity.AnnotationKind.SINGLE,
            factoryBeanName = AbstractLocalLeaderElector.LOCAL_FACTORY_BEAN_NAME,
        )
        val lastExtendDeadlineRef = AtomicReference(Instant.EPOCH)

        val delegate = object: ExtendDelegate {
            private val _lastExtendDeadline = lastExtendDeadlineRef
            override val lastExtendDeadline: AtomicReference<Instant> get() = _lastExtendDeadline
            override fun extend(lockAtMostFor: kotlin.time.Duration): ExtendOutcome {
                val extended = states.extendSingle(lockName, lockAtMostFor)
                return if (extended) {
                    ExtendOutcome.Extended(Instant.now().plusMillis(lockAtMostFor.inWholeMilliseconds))
                } else {
                    ExtendOutcome.NotHeld
                }
            }

            override fun isHeld(): Boolean = states.singleState(lockName).isOccupied
        }

        val handle = LeaderLockHandle.real(
            identity = identity,
            token = token,
            acquiredAtNanos = startedAtNanos,
            extendDelegate = delegate,
            auditLeaderId = auditLeaderId,
        )
        val watchdog = LeaderLeaseAutoExtender.start(options.autoExtend, options.leaseTime, delegate)
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
                states.releaseSingle(lockName)
                if (acquired) mutex.unlock()
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

    override fun state(lockName: String): LeaderState =
        states.singleState(lockName)

    override val supportsAuditLeaderState: Boolean = true

    /** lock-name overload는 구성된 node identity를 캡처해 suspend lease를 획득합니다. */
    override suspend fun tryAcquire(lockName: String): SuspendLeaderLeaseHandle? =
        tryAcquire(LeaderSlot(lockName, options.nodeId))

    /** caller slot identity를 보존해 suspend lease를 획득합니다. */
    override suspend fun tryAcquire(slot: LeaderSlot): SuspendLeaderLeaseHandle? =
        requestLeases.tryAcquireSuspend(slot, options.waitTime, options)
}
