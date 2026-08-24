package io.bluetape4k.leader.internal

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotBeNull
import org.junit.jupiter.api.Test

class LeaseAdmissionControllerTest {

    @Test
    fun `acquire reservation holds both attempt and queue capacity until terminal`() {
        val admission = LeaseAdmissionController(
            maxConcurrentAcquires = 1,
            maxAcquireQueueDepth = 1,
            maxActiveLeases = 2,
            maxResidualLeases = 2,
        )

        val reservation = admission.tryReserveAcquire()
        reservation.shouldNotBeNull()
        admission.tryReserveAcquire().shouldBeNull()
        admission.acquireInFlight shouldBeEqualTo 1

        reservation.close()
        reservation.close()
        admission.acquireInFlight shouldBeEqualTo 0
        admission.tryReserveAcquire().shouldNotBeNull()
    }

    @Test
    fun `active and residual reservations remain independently idempotent`() {
        val admission = LeaseAdmissionController(maxActiveLeases = 2, maxResidualLeases = 1)
        admission.effectiveActiveCapacity shouldBeEqualTo 1

        val active = admission.tryReserveActive()
        active.shouldNotBeNull()
        admission.tryReserveActive().shouldBeNull()
        val residual = admission.tryReserveResidual()
        residual.shouldNotBeNull()
        admission.tryReserveResidual().shouldBeNull()

        active.close()
        active.close()
        residual.close()
        residual.close()
        admission.activeLeases shouldBeEqualTo 0
        admission.residualLeases shouldBeEqualTo 0
    }

    @Test
    fun `mvc waiter reservation bounds servlet admission independently`() {
        val admission = LeaseAdmissionController(
            maxConcurrentAcquires = 2,
            maxMvcBlockingAcquires = 1,
        )

        val waiter = admission.tryReserveMvcWaiter()
        waiter.shouldNotBeNull()
        admission.mvcBlockingInFlight shouldBeEqualTo 1
        admission.tryReserveMvcWaiter().shouldBeNull()

        waiter.close()
        waiter.close()
        admission.mvcBlockingInFlight shouldBeEqualTo 0
        admission.tryReserveMvcWaiter().shouldNotBeNull()
    }
}
