package io.bluetape4k.leader.internal

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.leader.LeaseCleanupResult
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class LeaseCleanupBoundaryTest {

    @Test
    fun `successful release terminalizes reservation`() {
        val registry = ResidualLeaseRegistry(maxResidualLeases = 1)
        val scheduler = LeaseOperationScheduler(maxInFlight = 1, queueCapacity = 1, threadNamePrefix = "cleanup-test")
        val boundary = LeaseCleanupBoundaryImpl(
            scheduler = scheduler,
            residualRegistry = registry,
            release = { LeaseCleanupResult.RELEASED },
        )
        val reservation = registry.tryReserve().shouldNotBeNull()

        boundary.releaseWithin(1.seconds, reservation) shouldBeEqualTo LeaseCleanupResult.RELEASED
        reservation.isTerminal.shouldBeTrue()
        registry.activeCount shouldBeEqualTo 0
        scheduler.close()
    }

    @Test
    fun `deadline transfers reservation to residual registry`() {
        val registry = ResidualLeaseRegistry(maxResidualLeases = 1)
        val scheduler = LeaseOperationScheduler(maxInFlight = 1, queueCapacity = 1, threadNamePrefix = "cleanup-timeout")
        val boundary = LeaseCleanupBoundaryImpl(
            scheduler = scheduler,
            residualRegistry = registry,
            release = {
                Thread.sleep(100)
                LeaseCleanupResult.RELEASED
            },
        )
        val reservation = registry.tryReserve().shouldNotBeNull()

        boundary.releaseWithin(1.milliseconds, reservation) shouldBeEqualTo LeaseCleanupResult.RESIDUAL_TRANSFERRED
        registry.activeCount shouldBeEqualTo 1
        scheduler.close()
    }
}
