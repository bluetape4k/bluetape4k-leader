package io.bluetape4k.leader.redisson

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.strategies.FifoElectionStrategy
import io.bluetape4k.leader.strategy.strategies.FifoGroupElectionStrategy
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Test
import org.redisson.api.RMapCache
import org.redisson.api.RedissonClient
import java.util.concurrent.atomic.AtomicBoolean

class RedissonStrategicCandidateLookupFailureTest {

    @Test
    fun `blocking single candidate lookup backend exception is rethrown`() {
        val failure = IllegalStateException("redisson candidate lookup failed")
        val actionInvoked = AtomicBoolean(false)
        val client = redissonClient(failure)

        val thrown = assertFailsWith<IllegalStateException> {
            RedissonStrategicLeaderElector(client, "node-1")
                .runIfLeader("issue-785-single", FifoElectionStrategy) {
                    actionInvoked.set(true)
                    "must-not-run"
                }
        }

        thrown.message shouldBeEqualTo failure.message
        actionInvoked.get().shouldBeFalse()
    }

    @Test
    fun `blocking single candidate lookup cancellation is rethrown`() {
        val cancellation = CancellationException("redisson candidate lookup cancelled")
        val actionInvoked = AtomicBoolean(false)
        val client = redissonClient(cancellation)

        val thrown = assertFailsWith<CancellationException> {
            RedissonStrategicLeaderElector(client, "node-1")
                .runIfLeader("issue-785-single-cancel", FifoElectionStrategy) {
                    actionInvoked.set(true)
                    "must-not-run"
                }
        }

        thrown.message shouldBeEqualTo cancellation.message
        actionInvoked.get().shouldBeFalse()
    }

    @Test
    fun `blocking group candidate lookup Error is rethrown`() {
        val failure = AssertionError("redisson candidate lookup error")
        val actionInvoked = AtomicBoolean(false)
        val client = redissonClient(failure)

        val thrown = assertFailsWith<AssertionError> {
            RedissonStrategicLeaderGroupElector(client, "node-1")
                .runIfLeader("issue-785-group", FifoGroupElectionStrategy, maxLeaders = 1) {
                    actionInvoked.set(true)
                    "must-not-run"
                }
        }

        thrown.message shouldBeEqualTo failure.message
        actionInvoked.get().shouldBeFalse()
    }

    @Test
    fun `blocking single candidate codec failure is rethrown`() {
        val failure = IllegalStateException("redisson codec decode failed")
        val actionInvoked = AtomicBoolean(false)
        val client = redissonClient(failure)

        val thrown = assertFailsWith<IllegalStateException> {
            RedissonStrategicLeaderElector(client, "node-1")
                .runIfLeader("issue-785-codec", FifoElectionStrategy) {
                    actionInvoked.set(true)
                    "must-not-run"
                }
        }

        thrown.message shouldBeEqualTo failure.message
        actionInvoked.get().shouldBeFalse()
    }

    @Test
    fun `suspend single candidate lookup cancellation is rethrown`() = runSuspendIO {
        val cancellation = CancellationException("redisson suspend candidate lookup cancelled")
        val actionInvoked = AtomicBoolean(false)
        val client = redissonClient(cancellation)

        val thrown = assertFailsWith<CancellationException> {
            RedissonStrategicSuspendLeaderElector(client, "node-1")
                .runIfLeader("issue-785-suspend-single", FifoElectionStrategy) {
                    actionInvoked.set(true)
                    "must-not-run"
                }
        }

        thrown.message shouldBeEqualTo cancellation.message
        actionInvoked.get().shouldBeFalse()
    }

    @Test
    fun `suspend group candidate lookup backend exception is rethrown`() = runSuspendIO {
        val failure = IllegalStateException("redisson suspend candidate lookup failed")
        val actionInvoked = AtomicBoolean(false)
        val client = redissonClient(failure)

        val thrown = assertFailsWith<IllegalStateException> {
            RedissonStrategicSuspendLeaderGroupElector(client, "node-1")
                .runIfLeader("issue-785-suspend-group", FifoGroupElectionStrategy, maxLeaders = 1) {
                    actionInvoked.set(true)
                    "must-not-run"
                }
        }

        thrown.message shouldBeEqualTo failure.message
        actionInvoked.get().shouldBeFalse()
    }

    @Test
    fun `suspend group candidate lookup Error is rethrown`() = runSuspendIO {
        val failure = AssertionError("redisson suspend candidate lookup error")
        val actionInvoked = AtomicBoolean(false)
        val client = redissonClient(failure)

        val thrown = assertFailsWith<AssertionError> {
            RedissonStrategicSuspendLeaderGroupElector(client, "node-1")
                .runIfLeader("issue-785-suspend-group-error", FifoGroupElectionStrategy, maxLeaders = 1) {
                    actionInvoked.set(true)
                    "must-not-run"
                }
        }

        thrown.message shouldBeEqualTo failure.message
        actionInvoked.get().shouldBeFalse()
    }

    @Test
    fun `suspend group candidate codec failure is rethrown`() = runSuspendIO {
        val failure = IllegalStateException("redisson suspend codec decode failed")
        val actionInvoked = AtomicBoolean(false)
        val client = redissonClient(failure)

        val thrown = assertFailsWith<IllegalStateException> {
            RedissonStrategicSuspendLeaderGroupElector(client, "node-1")
                .runIfLeader("issue-785-codec-group", FifoGroupElectionStrategy, maxLeaders = 1) {
                    actionInvoked.set(true)
                    "must-not-run"
                }
        }

        thrown.message shouldBeEqualTo failure.message
        actionInvoked.get().shouldBeFalse()
    }

    private fun redissonClient(failure: Throwable): RedissonClient {
        val client = mockk<RedissonClient>()
        val cache = mockk<RMapCache<String, CandidateInfo>>()
        every { client.getMapCache<String, CandidateInfo>(any<String>()) } returns cache
        every { cache.readAllValues() } throws failure
        return client
    }
}
