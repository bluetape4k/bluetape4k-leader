package io.bluetape4k.leader.spring.observability

import io.bluetape4k.leader.LeaderElectionEvent
import io.bluetape4k.leader.LeaderElectionEventPublisher
import io.bluetape4k.leader.LeaderElectionListener
import io.bluetape4k.logging.KLogging
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * leader election listener callback을 status registry와 bounded shared event flow에 동시에 전달합니다.
 *
 * flow는 최대 128개 extra buffer를 사용하고 overflow 시 가장 오래된 event를 버립니다.
 *
 * @property registry callback에서 관찰한 lock 이름을 저장하는 status registry입니다.
 */
class LeaderElectionObservedEventPublisher(
    private val registry: LeaderElectionStatusRegistry,
): LeaderElectionListener, LeaderElectionEventPublisher {

    private companion object: KLogging()

    private val eventSubject = MutableSharedFlow<LeaderElectionEvent>(
        extraBufferCapacity = 128,
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
