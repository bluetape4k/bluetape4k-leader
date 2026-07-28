package io.bluetape4k.leader.spring.observability

import io.bluetape4k.leader.LeaderElectionEvent
import io.bluetape4k.leader.LeaderElectionEventPublisher
import io.bluetape4k.leader.LeaderElectionListener
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * `LeaderElectionObservedEventPublisher`는 Spring Boot integration의 leader election, route guard, metric, example workflow 계약을 설명합니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
 * @property registry Spring Boot integration 계약에서 사용하는 속성입니다.
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
