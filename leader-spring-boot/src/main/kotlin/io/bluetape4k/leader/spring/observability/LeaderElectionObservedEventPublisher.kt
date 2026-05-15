package io.bluetape4k.leader.spring.observability

import io.bluetape4k.leader.LeaderElectionEvent
import io.bluetape4k.leader.LeaderElectionEventPublisher
import io.bluetape4k.leader.LeaderElectionListener
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Publisher adapter that turns observed listener callbacks into leader election events.
 *
 * ## Behavior / Contract
 * - This adapter is publisher-only; it does not implement [io.bluetape4k.leader.LeaderElector],
 *   so it cannot make `LeaderElector` injection ambiguous.
 * - It emits events observed through [LeaderElectionListener] callbacks.
 * - Delivery is best-effort: the buffer keeps 64 events and drops the oldest buffered event
 *   under back-pressure.
 */
class LeaderElectionObservedEventPublisher(
    private val registry: LeaderElectionStatusRegistry,
) : LeaderElectionListener, LeaderElectionEventPublisher {

    private val eventSubject = MutableSharedFlow<LeaderElectionEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override val events: Flow<LeaderElectionEvent> = eventSubject.asSharedFlow()

    override fun onElected(lockName: String) {
        registry.register(lockName)
        eventSubject.tryEmit(LeaderElectionEvent.Elected(lockName))
    }

    override fun onRevoked(lockName: String) {
        registry.register(lockName)
        eventSubject.tryEmit(LeaderElectionEvent.Revoked(lockName))
    }

    override fun onSkipped(lockName: String) {
        registry.register(lockName)
        eventSubject.tryEmit(LeaderElectionEvent.Skipped(lockName))
    }
}
