package io.bluetape4k.leader

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.coroutines.LocalSuspendLeaderElector
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElector
import io.bluetape4k.leader.coroutines.withListeners
import io.bluetape4k.leader.local.LocalLeaderElector
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LeaderElectionListenerTest {

    @Test
    fun `LocalLeaderElector - 선출과 반납 callback 을 순서대로 발행한다`() {
        val election = LocalLeaderElector()
        val listener = RecordingListener()

        val handle = election.addListener(listener)
        val result = election.runIfLeader("listener-job") { "done" }

        result shouldBeEqualTo "done"
        listener.events shouldBeEqualTo listOf("elected:listener-job", "revoked:listener-job")

        handle.close()
        election.runIfLeader("listener-job") { "done-again" }
        listener.events shouldBeEqualTo listOf("elected:listener-job", "revoked:listener-job")
    }

    @Test
    fun `LocalLeaderElector - 리더 미획득 시 skipped callback 을 발행한다`() {
        val election = LocalLeaderElector(LeaderElectionOptions(waitTime = 50.milliseconds))
        val listener = RecordingListener()
        val lockName = "skip-listener-job"
        val holderReady = CountDownLatch(1)
        val holderDone = CountDownLatch(1)
        election.addListener(listener)

        val holder = Thread {
            election.runIfLeader(lockName) {
                holderReady.countDown()
                holderDone.await()
            }
        }.apply { start() }

        holderReady.await()
        val skipped = election.runIfLeader(lockName) { "not-called" }

        skipped shouldBeEqualTo null
        listener.events.contains("skipped:$lockName") shouldBeEqualTo true

        holderDone.countDown()
        holder.join()
    }

    @Test
    fun `ListeningLeaderElector - delegate 에 listener 이벤트를 더한다`() {
        val listener = RecordingListener()
        val election = StubLeaderElector(elected = true).withListeners(listener)

        val result = election.runIfLeader("decorated-job") { "done" }

        result shouldBeEqualTo "done"
        listener.events shouldBeEqualTo listOf("elected:decorated-job", "revoked:decorated-job")
    }

    @Test
    fun `ListeningLeaderElector - delegate 에 Flow 이벤트를 더한다`() = runSuspendIO {
        val election = StubLeaderElector(elected = true).withListeners()
        val collected = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(2_000.milliseconds) {
                election.events.take(2).toList()
            }
        }

        val result = election.runIfLeader("decorated-flow-job") { "done" }

        result shouldBeEqualTo "done"
        collected.await() shouldBeEqualTo listOf(
            LeaderElectionEvent.Elected("decorated-flow-job"),
            LeaderElectionEvent.Revoked("decorated-flow-job"),
        )
    }

    @Test
    fun `ListeningLeaderElector - delegate skip 을 listener 이벤트로 발행한다`() {
        val listener = RecordingListener()
        val election = StubLeaderElector(elected = false).withListeners(listener)

        val result = election.runIfLeader("decorated-skip-job") { "not-called" }

        result shouldBeEqualTo null
        listener.events shouldBeEqualTo listOf("skipped:decorated-skip-job")
    }

    @Test
    fun `ListeningLeaderElector - delegate skip 을 Flow 이벤트로 발행한다`() = runSuspendIO {
        val election = StubLeaderElector(elected = false).withListeners()
        val collected = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(2_000.milliseconds) {
                election.events.take(1).toList()
            }
        }

        val result = election.runIfLeader("decorated-flow-skip-job") { "not-called" }

        result shouldBeEqualTo null
        collected.await() shouldBeEqualTo listOf(
            LeaderElectionEvent.Skipped("decorated-flow-skip-job"),
        )
    }

    @Test
    fun `ListeningLeaderElector - async delegate 에 Flow 이벤트를 더한다`() = runSuspendIO {
        val executor = Executor { it.run() }
        val election = StubLeaderElector(elected = true).withListeners()
        val collected = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(2_000.milliseconds) {
                election.events.take(2).toList()
            }
        }

        val result = election.runAsyncIfLeader("decorated-async-flow-job", executor) {
            CompletableFuture.completedFuture("done")
        }.join()

        result shouldBeEqualTo "done"
        collected.await() shouldBeEqualTo listOf(
            LeaderElectionEvent.Elected("decorated-async-flow-job"),
            LeaderElectionEvent.Revoked("decorated-async-flow-job"),
        )
    }

    @Test
    fun `ListeningLeaderElector - async delegate skip 을 Flow 이벤트로 발행한다`() = runSuspendIO {
        val executor = Executor { it.run() }
        val election = StubLeaderElector(elected = false).withListeners()
        val collected = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(2_000.milliseconds) {
                election.events.take(1).toList()
            }
        }

        val result = election.runAsyncIfLeader("decorated-async-flow-skip-job", executor) {
            CompletableFuture.completedFuture("not-called")
        }.join()

        result shouldBeEqualTo null
        collected.await() shouldBeEqualTo listOf(
            LeaderElectionEvent.Skipped("decorated-async-flow-skip-job"),
        )
    }

    @Test
    fun `ListeningLeaderElector - async action 실패에도 Flow revoke 를 발행한다`() = runSuspendIO {
        val executor = Executor { it.run() }
        val election = StubLeaderElector(elected = true).withListeners()
        val collected = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(2_000.milliseconds) {
                election.events.take(2).toList()
            }
        }

        assertFailsWith<CompletionException> {
            election.runAsyncIfLeader("decorated-async-flow-failure-job", executor) {
                CompletableFuture.failedFuture<String>(IllegalStateException("boom"))
            }.join()
        }
        collected.await() shouldBeEqualTo listOf(
            LeaderElectionEvent.Elected("decorated-async-flow-failure-job"),
            LeaderElectionEvent.Revoked("decorated-async-flow-failure-job"),
        )
    }

    @Test
    fun `ListeningLeaderGroupElector - delegate 상태와 listener 이벤트를 더한다`() {
        val listener = RecordingListener()
        val election = StubLeaderGroupElector(elected = true).withListeners(listener)

        val result = election.runIfLeader("decorated-group-job") { "done" }

        result shouldBeEqualTo "done"
        election.maxLeaders shouldBeEqualTo 2
        election.activeCount("decorated-group-job") shouldBeEqualTo 1
        election.availableSlots("decorated-group-job") shouldBeEqualTo 1
        election.state("decorated-group-job") shouldBeEqualTo LeaderGroupState("decorated-group-job", 2, 1)
        listener.events shouldBeEqualTo listOf("elected:decorated-group-job", "revoked:decorated-group-job")
    }

    @Test
    fun `ListeningLeaderGroupElector - delegate 에 Flow 이벤트를 더한다`() = runSuspendIO {
        val election = StubLeaderGroupElector(elected = true).withListeners()
        val collected = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(2_000.milliseconds) {
                election.events.take(2).toList()
            }
        }

        val result = election.runIfLeader("decorated-group-flow-job") { "done" }

        result shouldBeEqualTo "done"
        collected.await() shouldBeEqualTo listOf(
            LeaderElectionEvent.Elected("decorated-group-flow-job"),
            LeaderElectionEvent.Revoked("decorated-group-flow-job"),
        )
    }

    @Test
    fun `ListeningLeaderGroupElector - delegate skip 을 listener 이벤트로 발행한다`() {
        val listener = RecordingListener()
        val election = StubLeaderGroupElector(elected = false).withListeners(listener)

        val result = election.runIfLeader("decorated-group-skip-job") { "not-called" }

        result shouldBeEqualTo null
        listener.events shouldBeEqualTo listOf("skipped:decorated-group-skip-job")
    }

    @Test
    fun `ListeningLeaderGroupElector - delegate skip 을 Flow 이벤트로 발행한다`() = runSuspendIO {
        val election = StubLeaderGroupElector(elected = false).withListeners()
        val collected = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(2_000.milliseconds) {
                election.events.take(1).toList()
            }
        }

        val result = election.runIfLeader("decorated-group-flow-skip-job") { "not-called" }

        result shouldBeEqualTo null
        collected.await() shouldBeEqualTo listOf(
            LeaderElectionEvent.Skipped("decorated-group-flow-skip-job"),
        )
    }

    @Test
    fun `ListeningLeaderGroupElector - async delegate 결과에도 listener 이벤트를 더한다`() {
        val listener = RecordingListener()
        val executor = Executor { it.run() }
        val election = StubLeaderGroupElector(elected = true).withListeners(listener)

        val result = election.runAsyncIfLeader("decorated-group-async-job", executor) {
            CompletableFuture.completedFuture("done")
        }.join()

        result shouldBeEqualTo "done"
        listener.events shouldBeEqualTo listOf("elected:decorated-group-async-job", "revoked:decorated-group-async-job")
    }

    @Test
    fun `ListeningLeaderGroupElector - async delegate skip 을 listener 이벤트로 발행한다`() {
        val listener = RecordingListener()
        val executor = Executor { it.run() }
        val election = StubLeaderGroupElector(elected = false).withListeners(listener)

        val result = election.runAsyncIfLeader("decorated-group-async-skip-job", executor) {
            CompletableFuture.completedFuture("not-called")
        }.join()

        result shouldBeEqualTo null
        listener.events shouldBeEqualTo listOf("skipped:decorated-group-async-skip-job")
    }

    @Test
    fun `ListeningLeaderGroupElector - async delegate 에 Flow 이벤트를 더한다`() = runSuspendIO {
        val executor = Executor { it.run() }
        val election = StubLeaderGroupElector(elected = true).withListeners()
        val collected = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(2_000.milliseconds) {
                election.events.take(2).toList()
            }
        }

        val result = election.runAsyncIfLeader("decorated-group-async-flow-job", executor) {
            CompletableFuture.completedFuture("done")
        }.join()

        result shouldBeEqualTo "done"
        collected.await() shouldBeEqualTo listOf(
            LeaderElectionEvent.Elected("decorated-group-async-flow-job"),
            LeaderElectionEvent.Revoked("decorated-group-async-flow-job"),
        )
    }

    @Test
    fun `ListeningLeaderGroupElector - async delegate skip 을 Flow 이벤트로 발행한다`() = runSuspendIO {
        val executor = Executor { it.run() }
        val election = StubLeaderGroupElector(elected = false).withListeners()
        val collected = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(2_000.milliseconds) {
                election.events.take(1).toList()
            }
        }

        val result = election.runAsyncIfLeader("decorated-group-async-flow-skip-job", executor) {
            CompletableFuture.completedFuture("not-called")
        }.join()

        result shouldBeEqualTo null
        collected.await() shouldBeEqualTo listOf(
            LeaderElectionEvent.Skipped("decorated-group-async-flow-skip-job"),
        )
    }

    @Test
    fun `ListeningLeaderGroupElector - async action 실패에도 Flow revoke 를 발행한다`() = runSuspendIO {
        val executor = Executor { it.run() }
        val election = StubLeaderGroupElector(elected = true).withListeners()
        val collected = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(2_000.milliseconds) {
                election.events.take(2).toList()
            }
        }

        assertFailsWith<CompletionException> {
            election.runAsyncIfLeader("decorated-group-async-flow-failure-job", executor) {
                CompletableFuture.failedFuture<String>(IllegalStateException("boom"))
            }.join()
        }
        collected.await() shouldBeEqualTo listOf(
            LeaderElectionEvent.Elected("decorated-group-async-flow-failure-job"),
            LeaderElectionEvent.Revoked("decorated-group-async-flow-failure-job"),
        )
    }

    @Test
    fun `LocalSuspendLeaderElector - Subject 로 선출 이벤트를 발행한다`() = runSuspendIO {
        val election = LocalSuspendLeaderElector()
        val collected = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(2_000.milliseconds) {
                election.events.take(2).toList()
            }
        }

        val result = election.runIfLeader("suspend-event-job") {
            delay(1.milliseconds)
            "done"
        }

        result shouldBeEqualTo "done"
        collected.await() shouldBeEqualTo listOf(
            LeaderElectionEvent.Elected("suspend-event-job", leaderId = LeaderNodeId.Default),
            LeaderElectionEvent.Revoked("suspend-event-job"),
        )
    }

    @Test
    fun `ListeningSuspendLeaderElector - delegate 에 callback listener 를 더한다`() = runSuspendIO {
        val listener = RecordingListener()
        val election = LocalSuspendLeaderElector().withListeners(listener)

        val result = election.runIfLeader("decorated-suspend-job") { "done" }

        result shouldBeEqualTo "done"
        listener.events shouldBeEqualTo listOf("elected:decorated-suspend-job", "revoked:decorated-suspend-job")
    }

    @Test
    fun `ListeningSuspendLeaderGroupElector - Subject 로 선출 이벤트를 발행한다`() = runSuspendIO {
        val listener = RecordingListener()
        val election = StubSuspendLeaderGroupElector(elected = true).withListeners(listener)
        val collected = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(2_000.milliseconds) {
                election.events.take(2).toList()
            }
        }

        val result = election.runIfLeader("decorated-suspend-group-job") { "done" }

        result shouldBeEqualTo "done"
        election.maxLeaders shouldBeEqualTo 2
        election.activeCount("decorated-suspend-group-job") shouldBeEqualTo 1
        election.availableSlots("decorated-suspend-group-job") shouldBeEqualTo 1
        election.state("decorated-suspend-group-job") shouldBeEqualTo LeaderGroupState("decorated-suspend-group-job", 2, 1)
        listener.events shouldBeEqualTo listOf(
            "elected:decorated-suspend-group-job",
            "revoked:decorated-suspend-group-job",
        )
        collected.await() shouldBeEqualTo listOf(
            LeaderElectionEvent.Elected("decorated-suspend-group-job"),
            LeaderElectionEvent.Revoked("decorated-suspend-group-job"),
        )
    }

    @Test
    fun `ListeningSuspendLeaderGroupElector - delegate skip 을 Subject 이벤트로 발행한다`() = runSuspendIO {
        val listener = RecordingListener()
        val election = StubSuspendLeaderGroupElector(elected = false).withListeners(listener)
        val collected = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(2_000) {
                election.events.take(1).toList()
            }
        }

        val result = election.runIfLeader("decorated-suspend-group-skip-job") { "not-called" }

        result shouldBeEqualTo null
        listener.events shouldBeEqualTo listOf("skipped:decorated-suspend-group-skip-job")
        collected.await() shouldBeEqualTo listOf(
            LeaderElectionEvent.Skipped("decorated-suspend-group-skip-job"),
        )
    }

    private class RecordingListener : LeaderElectionListener {
        val events = CopyOnWriteArrayList<String>()

        override fun onElected(lockName: String) {
            events += "elected:$lockName"
        }

        override fun onRevoked(lockName: String) {
            events += "revoked:$lockName"
        }

        override fun onSkipped(lockName: String) {
            events += "skipped:$lockName"
        }
    }

    private class StubLeaderElector(
        private val elected: Boolean,
    ) : LeaderElector {

        override fun state(lockName: String): LeaderState =
            LeaderState.empty(lockName)

        override fun <T> runIfLeader(lockName: String, action: () -> T): T? =
            if (elected) action() else null

        override fun <T> runAsyncIfLeader(
            lockName: String,
            executor: Executor,
            action: () -> CompletableFuture<T>,
        ): CompletableFuture<T?> =
            if (elected) {
                CompletableFuture.supplyAsync({ action() }, executor)
                    .thenCompose { it }
                    .thenApply<T?> { it }
            } else {
                CompletableFuture.completedFuture(null)
            }
    }

    private class StubLeaderGroupElector(
        private val elected: Boolean,
    ) : LeaderGroupElector {

        override val maxLeaders: Int = 2

        override fun activeCount(lockName: String): Int = 1

        override fun availableSlots(lockName: String): Int = 1

        override fun state(lockName: String): LeaderGroupState =
            LeaderGroupState(lockName, maxLeaders, activeCount(lockName))

        override fun <T> runIfLeader(lockName: String, action: () -> T): T? =
            if (elected) action() else null

        override fun <T> runAsyncIfLeader(
            lockName: String,
            executor: Executor,
            action: () -> CompletableFuture<T>,
        ): CompletableFuture<T?> =
            if (elected) {
                CompletableFuture.supplyAsync({ action() }, executor)
                    .thenCompose { it }
                    .thenApply<T?> { it }
            } else {
                CompletableFuture.completedFuture(null)
            }
    }

    private class StubSuspendLeaderGroupElector(
        private val elected: Boolean,
    ) : SuspendLeaderGroupElector {

        override val maxLeaders: Int = 2

        override fun activeCount(lockName: String): Int = 1

        override fun availableSlots(lockName: String): Int = 1

        override fun state(lockName: String): LeaderGroupState =
            LeaderGroupState(lockName, maxLeaders, activeCount(lockName))

        override suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T? =
            if (elected) action() else null
    }
}
