package io.bluetape4k.leader.coroutines

import io.bluetape4k.coroutines.flow.extensions.subject.PublishSubject
import io.bluetape4k.support.requireNotNull
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
import kotlinx.coroutines.flow.Flow

/**
 * `ListeningSuspendLeaderElector` 선언은 leader election 계약에서 사용되는 class입니다.
 *
 * API 이름과 `lock`, `lease`, `leader`, `slot`, `audit` 용어는 코드 계약과 동일하게 유지합니다.
 * @property delegate 실제 leader election 동작을 수행하는 위임 객체입니다.
 */
class ListeningSuspendLeaderElector(
    private val delegate: SuspendLeaderElector,
) : SuspendLeaderElector,
    SuspendLeaderLeaseAcquirerSupport,
    LeaderElectionListenerRegistry,
    LeaderElectionEventPublisher,
    LeaderBackendDiagnosticsAware {

    override val backendDiagnosticsProvider: LeaderBackendDiagnosticsProvider?
        get() = delegate.resolveLeaderBackendDiagnosticsProvider()

    private val listeners = LeaderElectionListenerSupport()
    private val eventSubject = PublishSubject<LeaderElectionEvent>()

    override val events: Flow<LeaderElectionEvent> = eventSubject

    override val supportsAuditLeaderState: Boolean
        get() = delegate.supportsAuditLeaderState

    override val leaseCapabilityAvailable: Boolean
        get() = (delegate as? SuspendLeaderLeaseAcquirerSupport)?.leaseCapabilityAvailable
            ?: delegate is SuspendLeaderLeaseAcquirer

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
 * `ListeningSuspendLeaderGroupElector` 선언은 leader election 계약에서 사용되는 class입니다.
 *
 * API 이름과 `lock`, `lease`, `leader`, `slot`, `audit` 용어는 코드 계약과 동일하게 유지합니다.
 * @property delegate 실제 leader election 동작을 수행하는 위임 객체입니다.
 */
class ListeningSuspendLeaderGroupElector(
    private val delegate: SuspendLeaderGroupElector,
) : SuspendLeaderGroupElector,
    LeaderElectionListenerRegistry,
    LeaderElectionEventPublisher,
    LeaderBackendDiagnosticsAware {

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
 * `SuspendLeaderElector`는 coroutine suspend leader election 실행자입니다.
 *
 * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @param listeners `listeners` 호출 또는 상태 계산에 필요한 값입니다.
 * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
 */
fun SuspendLeaderElector.withListeners(
    vararg listeners: LeaderElectionListener,
): ListeningSuspendLeaderElector =
    ListeningSuspendLeaderElector(this).apply {
        listeners.forEach { addListener(it) }
    }

/**
 * `SuspendLeaderGroupElector`는 여러 slot을 허용하는 coroutine group leader election 실행자입니다.
 *
 * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @param listeners `listeners` 호출 또는 상태 계산에 필요한 값입니다.
 * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
 */
fun SuspendLeaderGroupElector.withListeners(
    vararg listeners: LeaderElectionListener,
): ListeningSuspendLeaderGroupElector =
    ListeningSuspendLeaderGroupElector(this).apply {
        listeners.forEach { addListener(it) }
    }
