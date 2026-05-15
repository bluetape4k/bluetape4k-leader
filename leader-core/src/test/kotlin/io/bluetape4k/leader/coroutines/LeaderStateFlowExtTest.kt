package io.bluetape4k.leader.coroutines

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.LeaderElectionEvent
import io.bluetape4k.leader.LeaderElectionEventPublisher
import io.bluetape4k.leader.LeaderGroupState
import io.bluetape4k.leader.LeaderNodeId
import io.bluetape4k.leader.LeaderState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LeaderStateFlowExtTest {

    private class FakeEventPublisher : LeaderElectionEventPublisher {
        private val _events = MutableSharedFlow<LeaderElectionEvent>(replay = 0, extraBufferCapacity = 64)
        override val events: Flow<LeaderElectionEvent> = _events
        suspend fun emit(event: LeaderElectionEvent) = _events.emit(event)
        suspend fun awaitSubscriber() {
            _events.subscriptionCount.first { it > 0 }
        }
    }

    /**
     * Creates a leaderStateFlow in an independent scope (not a child of the calling scope),
     * runs [block], then cancels the flow scope. Prevents structured concurrency from blocking
     * the test's withTimeout waiting for the infinite collection coroutine.
     */
    private suspend fun withStateFlow(
        lockName: String = "my-lock",
        block: suspend (publisher: FakeEventPublisher, flow: StateFlow<LeaderState>) -> Unit,
    ) {
        val publisher = FakeEventPublisher()
        val scope = CoroutineScope(Dispatchers.IO + Job())
        try {
            val flow = publisher.leaderStateFlow(lockName, scope)
            block(publisher, flow)
        } finally {
            scope.cancel()
        }
    }

    private suspend fun withGroupStateFlow(
        lockName: String = "my-lock",
        maxLeaders: Int = 3,
        block: suspend (publisher: FakeEventPublisher, flow: StateFlow<LeaderGroupState>) -> Unit,
    ) {
        val publisher = FakeEventPublisher()
        val scope = CoroutineScope(Dispatchers.IO + Job())
        try {
            val flow = publisher.leaderGroupStateFlow(lockName, maxLeaders, scope)
            block(publisher, flow)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `initial state is empty`() = runSuspendIO {
        withStateFlow { _, flow ->
            flow.value.isEmpty.shouldBeTrue()
            flow.value.isOccupied.shouldBeFalse()
            flow.value.leader.shouldBeNull()
        }
    }

    @Test
    fun `non eager SharingStarted path delegates to stateIn`() = runSuspendIO {
        val publisher = FakeEventPublisher()
        val scope = CoroutineScope(Dispatchers.IO + Job())
        try {
            val flow = publisher.leaderStateFlow("my-lock", scope, SharingStarted.WhileSubscribed())
            flow.value.isEmpty.shouldBeTrue()

            val occupied = async(start = CoroutineStart.UNDISPATCHED) {
                flow.first { it.isOccupied }
            }
            publisher.awaitSubscriber()
            publisher.emit(LeaderElectionEvent.Elected("my-lock", leaderId = "node-while-subscribed"))

            occupied.await()
            flow.value.isOccupied.shouldBeTrue()
            flow.value.leader?.auditLeaderId shouldBeEqualTo "node-while-subscribed"
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `Elected event transitions to occupied`() = runSuspendIO {
        withStateFlow { publisher, flow ->
            publisher.emit(LeaderElectionEvent.Elected("my-lock", leaderId = "node-1"))
            flow.first { it.isOccupied }

            flow.value.isOccupied.shouldBeTrue()
            flow.value.leader?.auditLeaderId shouldBeEqualTo "node-1"
        }
    }

    @Test
    fun `Elected with null leaderId uses LeaderNodeId Default`() = runSuspendIO {
        withStateFlow { publisher, flow ->
            publisher.emit(LeaderElectionEvent.Elected("my-lock", leaderId = null))
            flow.first { it.isOccupied }

            flow.value.isOccupied.shouldBeTrue()
            flow.value.leader?.auditLeaderId shouldBeEqualTo LeaderNodeId.Default
        }
    }

    @Test
    fun `Elected with leaseExpiry populates leaseUntil`() = runSuspendIO {
        val expiry = Instant.now().plusSeconds(60)
        withStateFlow { publisher, flow ->
            publisher.emit(LeaderElectionEvent.Elected("my-lock", leaderId = "node-2", leaseExpiry = expiry))
            flow.first { it.isOccupied }

            flow.value.isOccupied.shouldBeTrue()
            flow.value.leader?.leaseUntil shouldBeEqualTo expiry
        }
    }

    @Test
    fun `Revoked event transitions back to empty`() = runSuspendIO {
        withStateFlow { publisher, flow ->
            publisher.emit(LeaderElectionEvent.Elected("my-lock", leaderId = "node-3"))
            flow.first { it.isOccupied }
            flow.value.isOccupied.shouldBeTrue()

            publisher.emit(LeaderElectionEvent.Revoked("my-lock"))
            flow.first { it.isEmpty }

            flow.value.isEmpty.shouldBeTrue()
            flow.value.leader.shouldBeNull()
        }
    }

    @Test
    fun `Skipped event does not change state`() = runSuspendIO {
        withStateFlow { publisher, flow ->
            publisher.emit(LeaderElectionEvent.Skipped("my-lock"))
            delay(50)

            flow.value.isEmpty.shouldBeTrue()
        }
    }

    @Test
    fun `events for other lockNames are filtered out`() = runSuspendIO {
        withStateFlow { publisher, flow ->
            publisher.emit(LeaderElectionEvent.Elected("other-lock", leaderId = "node-X"))
            delay(50)

            flow.value.isEmpty.shouldBeTrue()
        }
    }

    @Test
    fun `multiple Elected events keep latest occupied state`() = runSuspendIO {
        withStateFlow { publisher, flow ->
            publisher.emit(LeaderElectionEvent.Elected("my-lock", leaderId = "node-1"))
            flow.first { it.leader?.auditLeaderId == "node-1" }

            publisher.emit(LeaderElectionEvent.Elected("my-lock", leaderId = "node-2"))
            flow.first { it.leader?.auditLeaderId == "node-2" }

            flow.value.isOccupied.shouldBeTrue()
            flow.value.leader?.auditLeaderId shouldBeEqualTo "node-2"
        }
    }

    @Test
    fun `Skipped after Elected does not revert occupied state`() = runSuspendIO {
        withStateFlow { publisher, flow ->
            publisher.emit(LeaderElectionEvent.Elected("my-lock", leaderId = "node-5"))
            flow.first { it.isOccupied }
            flow.value.isOccupied.shouldBeTrue()

            publisher.emit(LeaderElectionEvent.Skipped("my-lock"))
            delay(50)

            flow.value.isOccupied.shouldBeTrue()
            flow.value.leader?.auditLeaderId shouldBeEqualTo "node-5"
        }
    }

    @Test
    fun `group state flow keeps active count after partial revoke`() = runSuspendIO {
        withGroupStateFlow(maxLeaders = 3) { publisher, flow ->
            publisher.emit(LeaderElectionEvent.Elected("my-lock", leaderId = "node-1"))
            flow.first { it.activeCount == 1 }

            publisher.emit(LeaderElectionEvent.Elected("my-lock", leaderId = "node-2"))
            flow.first { it.activeCount == 2 }

            publisher.emit(LeaderElectionEvent.Revoked("my-lock"))
            flow.first { it.activeCount == 1 }

            flow.value.isEmpty.shouldBeFalse()
            flow.value.availableSlots shouldBeEqualTo 2

            publisher.emit(LeaderElectionEvent.Revoked("my-lock"))
            flow.first { it.isEmpty }

            flow.value.activeCount shouldBeEqualTo 0
        }
    }

    @Test
    fun `group state flow caps active count at max leaders`() = runSuspendIO {
        withGroupStateFlow(maxLeaders = 2) { publisher, flow ->
            publisher.emit(LeaderElectionEvent.Elected("my-lock", leaderId = "node-1"))
            flow.first { it.activeCount == 1 }
            publisher.emit(LeaderElectionEvent.Elected("my-lock", leaderId = "node-2"))
            flow.first { it.activeCount == 2 }
            publisher.emit(LeaderElectionEvent.Elected("my-lock", leaderId = "node-3"))
            delay(50)

            flow.value.activeCount shouldBeEqualTo 2
            flow.value.isFull.shouldBeTrue()
        }
    }

    @Test
    fun `group state flow ignores skipped events and other lock names`() = runSuspendIO {
        withGroupStateFlow { publisher, flow ->
            publisher.emit(LeaderElectionEvent.Elected("other-lock", leaderId = "node-X"))
            publisher.emit(LeaderElectionEvent.Skipped("my-lock"))
            delay(50)

            flow.value shouldBeEqualTo LeaderGroupState("my-lock", maxLeaders = 3, activeCount = 0)
        }
    }
}
