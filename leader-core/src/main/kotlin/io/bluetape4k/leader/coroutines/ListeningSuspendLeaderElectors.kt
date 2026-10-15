package io.bluetape4k.leader.coroutines

import io.bluetape4k.coroutines.flow.extensions.subject.PublishSubject
import io.bluetape4k.leader.LeaderElectionEvent
import io.bluetape4k.leader.LeaderElectionEventPublisher
import io.bluetape4k.leader.LeaderElectionListener
import io.bluetape4k.leader.LeaderElectionListenerRegistry
import io.bluetape4k.leader.LeaderElectionListenerSupport
import io.bluetape4k.leader.LeaderGroupState
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.LeaderState
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsAware
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider
import io.bluetape4k.leader.diagnostics.resolveLeaderBackendDiagnosticsProvider
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.support.requireNotNull
import kotlinx.coroutines.flow.Flow

/**
 * suspend elector의 선거 결과를 delegate에 위임하고 listener와 `events` Flow로 lifecycle을 알립니다.
 * @property delegate 선거와 상태 조회를 수행할 suspend elector
 */
class ListeningSuspendLeaderElector(
    private val delegate: SuspendLeaderElector,
): SuspendLeaderElector,
   SuspendLeaderLeaseAcquirerSupport,
   LeaderElectionListenerRegistry,
   LeaderElectionEventPublisher,
   LeaderBackendDiagnosticsAware {

    companion object: KLoggingChannel()

    override val backendDiagnosticsProvider: LeaderBackendDiagnosticsProvider?
        get() = delegate.resolveLeaderBackendDiagnosticsProvider()

    private val listeners = LeaderElectionListenerSupport()
    private val eventSubject = PublishSubject<LeaderElectionEvent>()

    override val events: Flow<LeaderElectionEvent> = eventSubject

    override val supportsAuditLeaderState: Boolean
        get() = delegate.supportsAuditLeaderState

    override val leaseCapabilityAvailable: Boolean
        get() = ((delegate as? SuspendLeaderLeaseAcquirerSupport)?.leaseCapabilityAvailable
            ?: delegate) is SuspendLeaderLeaseAcquirer

    override val suspendLeaseAcquirerDelegate: SuspendLeaderLeaseAcquirer by lazy {
        (delegate as? SuspendLeaderLeaseAcquirer).requireNotNull {
            "The listening suspend elector delegate does not expose request-lease capability"
        }
    }

    override fun addListener(listener: LeaderElectionListener): AutoCloseable =
        listeners.addListener(listener)

    override fun removeListener(listener: LeaderElectionListener): Boolean =
        listeners.removeListener(listener)

    override fun state(lockName: String): LeaderState =
        delegate.state(lockName)

    override suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T? {
        var elected = false
        val result = delegate.runIfLeader(lockName) {
            elected = true
            val leader = delegate.state(lockName).leader
            listeners.notifyElected(lockName, leader)
            eventSubject.emit(LeaderElectionEvent.Elected.fromLease(lockName, leader))
            try {
                action()
            } finally {
                listeners.notifyRevoked(lockName)
                eventSubject.emit(LeaderElectionEvent.Revoked(lockName))
            }
        }
        if (!elected) {
            listeners.notifySkipped(lockName)
            eventSubject.emit(LeaderElectionEvent.Skipped(lockName))
        }
        return result
    }

    override suspend fun <T> runIfLeader(slot: LeaderSlot, action: suspend () -> T): T? {
        var elected = false
        val result = delegate.runIfLeader(slot) {
            elected = true
            val leader = delegate.state(slot.lockName).leader
            listeners.notifyElected(slot.lockName, leader)
            eventSubject.emit(LeaderElectionEvent.Elected.fromLease(slot.lockName, leader))
            try {
                action()
            } finally {
                listeners.notifyRevoked(slot.lockName)
                eventSubject.emit(LeaderElectionEvent.Revoked(slot.lockName))
            }
        }
        if (!elected) {
            listeners.notifySkipped(slot.lockName)
            eventSubject.emit(LeaderElectionEvent.Skipped(slot.lockName))
        }
        return result
    }

    override suspend fun <T> runIfLeaderResultSuspend(
        slot: LeaderSlot,
        action: suspend () -> T,
    ): LeaderRunResult<T> {
        var elected = false
        val result = delegate.runIfLeaderResultSuspend(slot) {
            elected = true
            val leader = delegate.state(slot.lockName).leader
            listeners.notifyElected(slot.lockName, leader)
            eventSubject.emit(LeaderElectionEvent.Elected.fromLease(slot.lockName, leader))
            try {
                action()
            } finally {
                listeners.notifyRevoked(slot.lockName)
                eventSubject.emit(LeaderElectionEvent.Revoked(slot.lockName))
            }
        }
        if (!elected && result is LeaderRunResult.Skipped) {
            listeners.notifySkipped(slot.lockName)
            eventSubject.emit(LeaderElectionEvent.Skipped(slot.lockName))
        }
        return result
    }
}

/**
 * suspend group elector의 선거 결과를 delegate에 위임하고 listener와 `events` Flow로 lifecycle을 알립니다.
 * @property delegate 그룹 선거와 상태 조회를 수행할 suspend group elector
 */
class ListeningSuspendLeaderGroupElector(
    private val delegate: SuspendLeaderGroupElector,
): SuspendLeaderGroupElector,
   LeaderElectionListenerRegistry,
   LeaderElectionEventPublisher,
   LeaderBackendDiagnosticsAware {

    companion object: KLoggingChannel()

    override val backendDiagnosticsProvider: LeaderBackendDiagnosticsProvider?
        get() = delegate.resolveLeaderBackendDiagnosticsProvider()

    private val listeners = LeaderElectionListenerSupport()
    private val eventSubject = PublishSubject<LeaderElectionEvent>()

    override val maxLeaders: Int get() = delegate.maxLeaders
    override val events: Flow<LeaderElectionEvent> = eventSubject

    override fun addListener(listener: LeaderElectionListener): AutoCloseable =
        listeners.addListener(listener)

    override fun removeListener(listener: LeaderElectionListener): Boolean =
        listeners.removeListener(listener)

    override fun activeCount(lockName: String): Int =
        delegate.activeCount(lockName)

    override fun availableSlots(lockName: String): Int =
        delegate.availableSlots(lockName)

    override fun state(lockName: String): LeaderGroupState =
        delegate.state(lockName)

    override suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T? {
        var elected = false
        val result = delegate.runIfLeader(lockName) {
            elected = true
            listeners.notifyElected(lockName, null)
            eventSubject.emit(LeaderElectionEvent.Elected.fromLease(lockName, null))
            try {
                action()
            } finally {
                listeners.notifyRevoked(lockName)
                eventSubject.emit(LeaderElectionEvent.Revoked(lockName))
            }
        }
        if (!elected) {
            listeners.notifySkipped(lockName)
            eventSubject.emit(LeaderElectionEvent.Skipped(lockName))
        }
        return result
    }
}

/**
 * 주어진 listeners를 등록한 suspend elector wrapper를 만듭니다.
 * @param listeners 선거 lifecycle 알림을 받을 listeners
 * @return 원래 elector에 listener 전달 기능을 추가한 wrapper
 */
fun SuspendLeaderElector.withListeners(
    vararg listeners: LeaderElectionListener,
): ListeningSuspendLeaderElector =
    ListeningSuspendLeaderElector(this).apply {
        listeners.forEach { addListener(it) }
    }

/**
 * 주어진 listeners를 등록한 suspend group elector wrapper를 만듭니다.
 * @param listeners 그룹 선거 lifecycle 알림을 받을 listeners
 * @return 원래 group elector에 listener 전달 기능을 추가한 wrapper
 */
fun SuspendLeaderGroupElector.withListeners(
    vararg listeners: LeaderElectionListener,
): ListeningSuspendLeaderGroupElector =
    ListeningSuspendLeaderGroupElector(this).apply {
        listeners.forEach { addListener(it) }
    }
