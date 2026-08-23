package io.bluetape4k.leader.spring.observability

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderState
import io.bluetape4k.leader.metrics.SkipReason
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

class LeaderElectionStatusEndpointTest {

    private val now = Instant.parse("2026-07-15T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val registry = LeaderElectionStatusRegistry(listOf("job"))
    private val elector = TestLeaderElector()

    @Test
    fun `endpoint returns same acquisition failure view without lock names`() {
        val window = LeaderAcquisitionFailureWindow(Duration.ofMinutes(5), clock, capacity = 4)
        window.onLockNotAcquired("tenant-secret-job", LeaderElectionOptions(), SkipReason.BACKEND_ERROR)
        val endpoint = LeaderElectionStatusEndpoint(elector, registry, window)

        val response = endpoint.leaderElectionStatus()

        response.acquisitionFailures.count shouldBeEqualTo 1
        response.acquisitionFailures.lastFailureAt shouldBeEqualTo now
        response.acquisitionFailures.window shouldBeEqualTo Duration.ofMinutes(5)
        response.acquisitionFailures.capacity shouldBeEqualTo 4
        response.acquisitionFailures.overflowed.shouldBeFalse()
        response.acquisitionFailures.toString().contains("tenant-secret-job").shouldBeFalse()
    }

    @Test
    fun `legacy response constructor and copy preserve empty acquisition view`() {
        val legacy = LeaderElectionStatusResponse(listOf(LeaderElectionLockStatus("job", "Empty", null, null)))
        val legacyFourArgument = LeaderElectionStatusResponse(legacy.locks, "backend", "provider", true)

        legacy.acquisitionFailures.count shouldBeEqualTo 0
        legacy.copy(legacy.locks).acquisitionFailures shouldBeEqualTo legacy.acquisitionFailures
        legacyFourArgument.copy(legacy.locks, "backend", "provider", true).acquisitionFailures.count shouldBeEqualTo 0
    }

    private class TestLeaderElector : LeaderElector {
        override val supportsAuditLeaderState: Boolean = true

        override fun <T> runIfLeader(lockName: String, action: () -> T): T? = action()

        override fun <T> runAsyncIfLeader(
            lockName: String,
            executor: Executor,
            action: () -> CompletableFuture<T>,
        ): CompletableFuture<T?> = action().thenApply { it }

        override fun state(lockName: String): LeaderState = LeaderState.empty(lockName)
    }
}
