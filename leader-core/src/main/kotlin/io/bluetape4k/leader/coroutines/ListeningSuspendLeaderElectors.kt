package io.bluetape4k.leader.coroutines

import io.bluetape4k.coroutines.flow.extensions.subject.PublishSubject
import io.bluetape4k.leader.LeaderElectionEvent
import io.bluetape4k.leader.LeaderElectionEventPublisher
import io.bluetape4k.leader.LeaderElectionListener
import io.bluetape4k.leader.LeaderElectionListenerRegistry
import io.bluetape4k.leader.LeaderElectionListenerSupport
import io.bluetape4k.leader.LeaderGroupState
import io.bluetape4k.leader.LeaderState
import kotlinx.coroutines.flow.Flow

/**
 * [SuspendLeaderElector]에 [LeaderElectionListener] dispatch와 hot 이벤트 stream을 추가하는 데코레이터입니다.
 */
class ListeningSuspendLeaderElector(
    private val delegate: SuspendLeaderElector,
) : SuspendLeaderElector, LeaderElectionListenerRegistry, LeaderElectionEventPublisher {

    private val listeners = LeaderElectionListenerSupport()
    private val eventSubject = PublishSubject<LeaderElectionEvent>()

    override val events: Flow<LeaderElectionEvent> = eventSubject

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
            listeners.notifyElected(lockName)
            eventSubject.emit(LeaderElectionEvent.Elected(lockName))
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
 * [SuspendLeaderGroupElector]에 [LeaderElectionListener] dispatch와 hot 이벤트 stream을 추가하는 데코레이터입니다.
 */
class ListeningSuspendLeaderGroupElector(
    private val delegate: SuspendLeaderGroupElector,
) : SuspendLeaderGroupElector, LeaderElectionListenerRegistry, LeaderElectionEventPublisher {

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
            listeners.notifyElected(lockName)
            eventSubject.emit(LeaderElectionEvent.Elected(lockName))
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
 * [SuspendLeaderElector]를 listener-aware 데코레이터로 감쌉니다.
 */
fun SuspendLeaderElector.withListeners(
    vararg listeners: LeaderElectionListener,
): ListeningSuspendLeaderElector =
    ListeningSuspendLeaderElector(this).apply {
        listeners.forEach { addListener(it) }
    }

/**
 * [SuspendLeaderGroupElector]를 listener-aware 데코레이터로 감쌉니다.
 */
fun SuspendLeaderGroupElector.withListeners(
    vararg listeners: LeaderElectionListener,
): ListeningSuspendLeaderGroupElector =
    ListeningSuspendLeaderGroupElector(this).apply {
        listeners.forEach { addListener(it) }
    }
