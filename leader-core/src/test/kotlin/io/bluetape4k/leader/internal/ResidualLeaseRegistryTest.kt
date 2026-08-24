package io.bluetape4k.leader.internal

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.leader.LeaseCleanupResult
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

class ResidualLeaseRegistryTest {

    @Test
    fun `reserved residual can be transferred and reconciled exactly once`() {
        val registry = ResidualLeaseRegistry(maxResidualLeases = 1, retention = 10.seconds)
        val reservation = registry.tryReserve().shouldNotBeNull()
        val entry = registry.transfer(reservation, acquiredAtNanos = 1L, transferAtNanos = 2L).shouldNotBeNull()

        registry.activeCount shouldBeEqualTo 1
        registry.tryReserve().shouldBeNull()
        registry.reconcile(entry, LeaseCleanupResult.RELEASED).shouldBeTrue()
        registry.reconcile(entry, LeaseCleanupResult.RELEASED).shouldBeFalse()
        registry.activeCount shouldBeEqualTo 0
        registry.tryReserve().shouldNotBeNull().terminalize()
    }

    @Test
    fun `unknown residual is quarantined at retention and evicted only after proof`() {
        var now = 100L
        val registry = ResidualLeaseRegistry(
            maxResidualLeases = 2,
            retention = 10.seconds,
            monotonicNanos = { now },
        )
        val reservation = registry.tryReserve().shouldNotBeNull()
        val entry = registry.transfer(reservation, acquiredAtNanos = 100L, transferAtNanos = 100L).shouldNotBeNull()

        now = 100L + 11.seconds.inWholeNanoseconds
        registry.expireDue()
        entry.state shouldBeEqualTo ResidualLeaseState.QUARANTINED_UNKNOWN
        registry.activeCount shouldBeEqualTo 1
        registry.confirmProof(entry).shouldBeTrue()
        entry.state shouldBeEqualTo ResidualLeaseState.EVICTED
        registry.activeCount shouldBeEqualTo 0
        registry.confirmProof(entry).shouldBeFalse()
    }

    @Test
    fun `terminalizing a residual invokes its composite owner exactly once`() {
        val registry = ResidualLeaseRegistry(maxResidualLeases = 1, retention = 10.seconds)
        val reservation = registry.tryReserve().shouldNotBeNull()
        var terminalized = 0
        val entry = registry.transfer(
            reservation,
            onTerminalized = { terminalized++ },
        ).shouldNotBeNull()

        registry.reconcile(entry, LeaseCleanupResult.RELEASED).shouldBeTrue()
        reservation.terminalize()
        terminalized shouldBeEqualTo 1
        registry.activeCount shouldBeEqualTo 0
    }
}
