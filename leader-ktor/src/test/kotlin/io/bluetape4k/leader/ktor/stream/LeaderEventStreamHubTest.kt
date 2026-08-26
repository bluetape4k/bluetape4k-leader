package io.bluetape4k.leader.ktor.stream

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.leader.LeaderElectionEvent
import io.bluetape4k.leader.LeaderElectionEventPublisher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class LeaderEventStreamHubTest {

    @Test
    fun `sequence는 monotonic이고 capacity를 넘으면 오래된 replay를 버린다`() = runTest {
        val publisher = FakePublisher()
        val hub = LeaderEventStreamHub(publisher, capacity = 2, scope = backgroundScope)
        hub.awaitStarted()

        publisher.emit(LeaderElectionEvent.Skipped("a"))
        publisher.emit(LeaderElectionEvent.Skipped("b"))
        publisher.emit(LeaderElectionEvent.Skipped("c"))
        runCurrent()

        hub.replay(afterSequence = null)
            .filterIsInstance<LeaderStreamItem.Event>()
            .map { it.sequence } shouldBeEqualTo listOf(2L, 3L)
        hub.replay(afterSequence = null)
            .filterIsInstance<LeaderStreamItem.Event>()
            .map { it.event.lockName } shouldBeEqualTo listOf("b", "c")
    }

    @Test
    fun `replay과 live handoff에는 gap과 duplicate가 없다`() = runTest {
        val publisher = FakePublisher()
        val hub = LeaderEventStreamHub(publisher, capacity = 8, scope = backgroundScope)
        hub.awaitStarted()
        val observed = mutableListOf<Long>()
        val collector = launch {
            hub.subscribe(lockName = "job", afterSequence = null)
                .filterIsInstance<LeaderStreamItem.Event>()
                .take(3)
                .collect { observed += it.sequence }
        }
        hub.awaitSubscriberCount(1)

        publisher.emit(LeaderElectionEvent.Skipped("job"))
        publisher.emit(LeaderElectionEvent.Revoked("job"))
        publisher.emit(LeaderElectionEvent.Skipped("job"))
        collector.join()

        observed shouldBeEqualTo listOf(1L, 2L, 3L)
        hub.subscriberCount() shouldBeEqualTo 0
    }

    @Test
    fun `보존 범위를 벗어난 cursor는 replay_gap control event를 만든다`() = runTest {
        val publisher = FakePublisher()
        val hub = LeaderEventStreamHub(publisher, capacity = 2, scope = backgroundScope)
        hub.awaitStarted()
        repeat(4) { publisher.emit(LeaderElectionEvent.Skipped("job")) }
        runCurrent()

        val items = hub.replay(afterSequence = 0L)
        items.first().kind shouldBeEqualTo LeaderStreamItem.Kind.REPLAY_GAP
        val gap = items.first() as LeaderStreamItem.Control
        gap.from shouldBeEqualTo 1L
        gap.to shouldBeEqualTo 2L
        items.drop(1)
            .filterIsInstance<LeaderStreamItem.Event>()
            .map { it.sequence } shouldBeEqualTo listOf(3L, 4L)
    }

    @Test
    fun `lock filter는 해당 lock event만 전달하고 all lock은 명시적으로 허용한다`() = runTest {
        val publisher = FakePublisher()
        val hub = LeaderEventStreamHub(publisher, capacity = 8, scope = backgroundScope)
        hub.awaitStarted()
        publisher.emit(LeaderElectionEvent.Skipped("job-a"))
        publisher.emit(LeaderElectionEvent.Skipped("job-b"))
        runCurrent()

        hub.replay(afterSequence = null, lockName = "job-a")
            .filterIsInstance<LeaderStreamItem.Event>()
            .map { it.event.lockName } shouldBeEqualTo listOf("job-a")
        assertFailsWith<IllegalArgumentException> {
            hub.acquireConnection(lockName = null, afterSequence = null)
        }

        val allLocksHub = LeaderEventStreamHub(
            publisher = FakePublisher(),
            capacity = 8,
            scope = backgroundScope,
            allLocksEnabled = true,
        )
        allLocksHub.awaitStarted()
        allLocksHub.close()
        allLocksHub.awaitClosed()
    }

    @Test
    fun `capacity 0은 cursor가 유효해도 replay gap 없이 live only다`() = runTest {
        val publisher = FakePublisher()
        val hub = LeaderEventStreamHub(publisher, capacity = 0, scope = backgroundScope)
        hub.awaitStarted()
        publisher.emit(LeaderElectionEvent.Skipped("job"))

        hub.replay(afterSequence = 0L) shouldBeEqualTo emptyList()
        hub.replay(afterSequence = 1L) shouldBeEqualTo emptyList()

        val observed = mutableListOf<Long>()
        val collector = launch {
            hub.subscribe(lockName = "job", afterSequence = 0L)
                .filterIsInstance<LeaderStreamItem.Event>()
                .take(1)
                .collect { observed += it.sequence }
        }
        hub.awaitSubscriberCount(1)
        publisher.emit(LeaderElectionEvent.Revoked("job"))
        collector.join()
        observed shouldBeEqualTo listOf(2L)
    }

    @Test
    fun `느린 consumer는 bounded channel에서 오래된 item을 drop한다`() = runTest {
        val publisher = FakePublisher()
        val hub = LeaderEventStreamHub(publisher, capacity = 1, scope = backgroundScope)
        hub.awaitStarted()
        val connection = hub.acquireConnection(lockName = "job", afterSequence = null)

        publisher.emit(LeaderElectionEvent.Skipped("job"))
        publisher.emit(LeaderElectionEvent.Revoked("job"))
        publisher.emit(LeaderElectionEvent.Skipped("job"))
        runCurrent()

        (hub.droppedItemCount > 0L).shouldBeTrue()
        hub.releaseConnection(connection)
        hub.subscriberCount() shouldBeEqualTo 0
    }

    @Test
    fun `connection admission과 cleanup은 상한을 지키고 permit을 반환한다`() = runTest {
        val publisher = FakePublisher()
        val hub = LeaderEventStreamHub(
            publisher = publisher,
            capacity = 2,
            scope = backgroundScope,
            maxConnections = 1,
        )
        hub.awaitStarted()
        val first = hub.acquireConnection(lockName = "job", afterSequence = null)

        assertFailsWith<LeaderEventStreamConnectionLimitException> {
            hub.acquireConnection(lockName = "job", afterSequence = null)
        }
        hub.releaseConnection(first)
        val second = hub.acquireConnection(lockName = "job", afterSequence = null)
        hub.releaseConnection(second)
        hub.subscriberCount() shouldBeEqualTo 0
    }

    @Test
    fun `cancelled subscription은 connection permit을 반환한다`() = runTest {
        val publisher = FakePublisher()
        val hub = LeaderEventStreamHub(
            publisher = publisher,
            capacity = 2,
            scope = backgroundScope,
            maxConnections = 1,
        )
        hub.awaitStarted()
        val collector = launch {
            hub.subscribe(lockName = "job", afterSequence = null).collect { }
        }
        hub.awaitSubscriberCount(1)
        collector.cancelAndJoin()

        hub.subscriberCount() shouldBeEqualTo 0
        val connection = hub.acquireConnection(lockName = "job", afterSequence = null)
        hub.releaseConnection(connection)
    }

    @Test
    fun `close는 collector와 subscriber를 idempotent하게 종료한다`() = runTest {
        val publisher = FakePublisher()
        val hub = LeaderEventStreamHub(publisher, capacity = 2, scope = backgroundScope)
        hub.awaitStarted()
        val connection = hub.acquireConnection(lockName = "job", afterSequence = null)

        hub.close()
        hub.close()
        hub.awaitClosed()

        hub.subscriberCount() shouldBeEqualTo 0
        connection.channel.receiveCatching().isClosed.shouldBeTrue()
        assertFailsWith<LeaderEventStreamClosedException> {
            hub.acquireConnection(lockName = "job", afterSequence = null)
        }
    }

    @Test
    fun `cursor parser는 blank와 non negative decimal만 허용한다`() {
        parseLeaderEventStreamCursor(null) shouldBeEqualTo null
        parseLeaderEventStreamCursor("") shouldBeEqualTo null
        parseLeaderEventStreamCursor(" 42 ") shouldBeEqualTo 42L
        assertFailsWith<IllegalArgumentException> { parseLeaderEventStreamCursor("-1") }
        assertFailsWith<IllegalArgumentException> { parseLeaderEventStreamCursor("1.0") }
        assertFailsWith<IllegalArgumentException> { parseLeaderEventStreamCursor("9223372036854775808") }
    }

    private class FakePublisher : LeaderElectionEventPublisher {
        private val source = MutableSharedFlow<LeaderElectionEvent>(extraBufferCapacity = 64)

        override val events: Flow<LeaderElectionEvent> = source.asSharedFlow()

        suspend fun emit(event: LeaderElectionEvent) {
            source.emit(event)
        }
    }
}
