package io.bluetape4k.leader.spring.observability

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.javatimes.minutes
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderState
import io.bluetape4k.leader.metrics.SkipReason
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

class LeaderElectionStatusEndpointTest {

    companion object: KLogging()

    private val now = Instant.parse("2026-07-15T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val registry = LeaderElectionStatusRegistry(listOf("job"))
    private val elector = TestLeaderElector()

    @Test
    fun `endpoint returns same acquisition failure view without lock names`() {
        val window = LeaderAcquisitionFailureWindow(5.minutes(), clock, capacity = 4)
        window.onLockNotAcquired("tenant-secret-job", LeaderElectionOptions(), SkipReason.BACKEND_ERROR)
        val endpoint = LeaderElectionStatusEndpoint(elector, registry, window)

        val response = endpoint.leaderElectionStatus()

        log.debug { "response=$response" }
        response.acquisitionFailures.count shouldBeEqualTo 1
        response.acquisitionFailures.lastFailureAt shouldBeEqualTo now
        response.acquisitionFailures.window shouldBeEqualTo 5.minutes()
        response.acquisitionFailures.capacity shouldBeEqualTo 4
        response.acquisitionFailures.overflowed.shouldBeFalse()
        response.acquisitionFailures.toString() shouldNotContain "tenant-secret-job"
    }

    @Test
    fun `legacy response constructor and copy preserve empty acquisition view`() {
        val legacy = LeaderElectionStatusResponse(listOf(LeaderElectionLockStatus("job", "Empty", null, null)))
        val legacyFourArgument = LeaderElectionStatusResponse(legacy.locks, "backend", "provider", true)

        log.debug { "legacy=$legacy" }
        log.debug { "legacyFourArg=$legacyFourArgument" }

        legacy.acquisitionFailures.count shouldBeEqualTo 0
        legacy.copy(legacy.locks).acquisitionFailures shouldBeEqualTo legacy.acquisitionFailures
        legacyFourArgument.copy(legacy.locks, "backend", "provider", true).acquisitionFailures.count shouldBeEqualTo 0
    }

    private class TestLeaderElector: LeaderElector {
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
