package io.bluetape4k.leader.lettuce

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.strategy.strategies.FifoElectionStrategy
import io.bluetape4k.leader.strategy.strategies.FifoGroupElectionStrategy
import io.lettuce.core.KeyValue
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.reactive.RedisReactiveCommands
import io.lettuce.core.api.sync.RedisCommands
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import java.util.concurrent.atomic.AtomicBoolean

class LettuceStrategicCandidateLookupFailureTest {

    @Test
    fun `blocking single candidate lookup backend exception is rethrown`() {
        val failure = IllegalStateException("lettuce candidate lookup failed")
        val actionInvoked = AtomicBoolean(false)
        val connection = blockingConnection(failure)

        val thrown = assertFailsWith<IllegalStateException> {
            LettuceStrategicLeaderElector(connection, "node-1")
                .runIfLeader("issue-785-single", FifoElectionStrategy) {
                    actionInvoked.set(true)
                    "must-not-run"
                }
        }

        thrown shouldBeEqualTo failure
        actionInvoked.get().shouldBeFalse()
    }

    @Test
    fun `blocking single candidate lookup cancellation is rethrown`() {
        val cancellation = CancellationException("lettuce candidate lookup cancelled")
        val actionInvoked = AtomicBoolean(false)
        val connection = blockingConnection(cancellation)

        val thrown = assertFailsWith<CancellationException> {
            LettuceStrategicLeaderElector(connection, "node-1")
                .runIfLeader("issue-785-single-cancel", FifoElectionStrategy) {
                    actionInvoked.set(true)
                    "must-not-run"
                }
        }

        thrown shouldBeEqualTo cancellation
        actionInvoked.get().shouldBeFalse()
    }

    @Test
    fun `blocking group candidate lookup Error is rethrown`() {
        val failure = AssertionError("lettuce candidate lookup error")
        val actionInvoked = AtomicBoolean(false)
        val connection = blockingConnection(failure)

        val thrown = assertFailsWith<AssertionError> {
            LettuceStrategicLeaderGroupElector(connection, "node-1")
                .runIfLeader("issue-785-group", FifoGroupElectionStrategy, maxLeaders = 1) {
                    actionInvoked.set(true)
                    "must-not-run"
                }
        }

        thrown shouldBeEqualTo failure
        actionInvoked.get().shouldBeFalse()
    }

    @Test
    fun `blocking single candidate codec failure is rethrown`() {
        val actionInvoked = AtomicBoolean(false)
        val connection = blockingCodecFailureConnection()

        val thrown = assertFailsWith<IllegalArgumentException> {
            LettuceStrategicLeaderElector(connection, "node-1")
                .runIfLeader("issue-785-codec", FifoElectionStrategy) {
                    actionInvoked.set(true)
                    "must-not-run"
                }
        }

        thrown.message shouldBeEqualTo "CandidateInfo 인코딩 형식 오류: malformed"
        actionInvoked.get().shouldBeFalse()
    }

    @Test
    fun `suspend single candidate lookup cancellation is rethrown`() = runSuspendIO {
        val cancellation = CancellationException("lettuce suspend candidate lookup cancelled")
        val actionInvoked = AtomicBoolean(false)
        val connection = suspendConnection(cancellation)

        val thrown = assertFailsWith<CancellationException> {
            LettuceStrategicSuspendLeaderElector(connection, "node-1")
                .runIfLeader("issue-785-suspend-single", FifoElectionStrategy) {
                    actionInvoked.set(true)
                    "must-not-run"
                }
        }

        thrown shouldBeEqualTo cancellation
        actionInvoked.get().shouldBeFalse()
    }

    @Test
    fun `suspend group candidate lookup backend exception is rethrown`() = runSuspendIO {
        val failure = IllegalStateException("lettuce suspend candidate lookup failed")
        val actionInvoked = AtomicBoolean(false)
        val connection = suspendConnection(failure)

        val thrown = assertFailsWith<IllegalStateException> {
            LettuceStrategicSuspendLeaderGroupElector(connection, "node-1")
                .runIfLeader("issue-785-suspend-group", FifoGroupElectionStrategy, maxLeaders = 1) {
                    actionInvoked.set(true)
                    "must-not-run"
                }
        }

        thrown shouldBeEqualTo failure
        actionInvoked.get().shouldBeFalse()
    }

    @Test
    fun `suspend group candidate lookup Error is rethrown`() = runSuspendIO {
        val failure = AssertionError("lettuce suspend candidate lookup error")
        val actionInvoked = AtomicBoolean(false)
        val connection = suspendConnection(failure)

        val thrown = assertFailsWith<AssertionError> {
            LettuceStrategicSuspendLeaderGroupElector(connection, "node-1")
                .runIfLeader("issue-785-suspend-group-error", FifoGroupElectionStrategy, maxLeaders = 1) {
                    actionInvoked.set(true)
                    "must-not-run"
                }
        }

        thrown shouldBeEqualTo failure
        actionInvoked.get().shouldBeFalse()
    }

    @Test
    fun `suspend group candidate codec failure is rethrown`() = runSuspendIO {
        val actionInvoked = AtomicBoolean(false)
        val connection = suspendCodecFailureConnection()

        val thrown = assertFailsWith<IllegalArgumentException> {
            LettuceStrategicSuspendLeaderGroupElector(connection, "node-1")
                .runIfLeader("issue-785-codec-group", FifoGroupElectionStrategy, maxLeaders = 1) {
                    actionInvoked.set(true)
                    "must-not-run"
                }
        }

        thrown.message shouldBeEqualTo "CandidateInfo 인코딩 형식 오류: malformed"
        actionInvoked.get().shouldBeFalse()
    }

    private fun blockingConnection(failure: Throwable): StatefulRedisConnection<String, String> {
        val connection = mockk<StatefulRedisConnection<String, String>>()
        val commands = mockk<RedisCommands<String, String>>()
        every { connection.sync() } returns commands
        every { commands.smembers(any()) } throws failure
        return connection
    }

    private fun blockingCodecFailureConnection(): StatefulRedisConnection<String, String> {
        val connection = mockk<StatefulRedisConnection<String, String>>()
        val commands = mockk<RedisCommands<String, String>>()
        every { connection.sync() } returns commands
        every { commands.smembers(any()) } returns setOf("node-1")
        every { commands.mget(any<String>()) } returns listOf(
            KeyValue.just(
                LettuceCandidateKeyCodec.candidateKey(
                    LettuceCandidateRegistry.DEFAULT_KEY_PREFIX,
                    "issue-785-codec",
                    "node-1",
                ),
                "malformed",
            ),
        )
        return connection
    }

    private fun suspendConnection(failure: Throwable): StatefulRedisConnection<String, String> {
        val connection = mockk<StatefulRedisConnection<String, String>>()
        val reactive = mockk<RedisReactiveCommands<String, String>>()
        every { connection.reactive() } returns reactive
        every { reactive.smembers(any()) } returns Flux.error(failure)
        return connection
    }

    private fun suspendCodecFailureConnection(): StatefulRedisConnection<String, String> {
        val connection = mockk<StatefulRedisConnection<String, String>>()
        val reactive = mockk<RedisReactiveCommands<String, String>>()
        every { connection.reactive() } returns reactive
        every { reactive.smembers(any()) } returns Flux.just("node-1")
        every { reactive.mget(any<String>()) } returns Flux.just(
            KeyValue.just(
                LettuceCandidateKeyCodec.candidateKey(
                    LettuceSuspendCandidateRegistry.GROUP_KEY_PREFIX,
                    "issue-785-codec-group",
                    "node-1",
                ),
                "malformed",
            ),
        )
        return connection
    }
}
