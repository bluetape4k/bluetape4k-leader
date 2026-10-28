package io.bluetape4k.leader.internal

import io.bluetape4k.concurrent.get
import io.bluetape4k.leader.LeaseCleanupBoundary
import io.bluetape4k.leader.LeaseCleanupReservation
import io.bluetape4k.leader.LeaseCleanupResult
import io.bluetape4k.leader.utils.safePlus
import io.bluetape4k.logging.KLogging
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

/** deadline을 인식하고 scheduler/backend timeout 시 residual로 넘기는 cleanup 구현입니다. */
class LeaseCleanupBoundaryImpl private constructor(
    private val scheduler: LeaseOperationScheduler,
    private val residualRegistry: ResidualLeaseRegistry,
    private val release: () -> LeaseCleanupResult,
    private val deadlineAwareRelease: ((Long) -> LeaseCleanupResult)?,
): LeaseCleanupBoundary {

    companion object: KLogging() {
        operator fun invoke(
            scheduler: LeaseOperationScheduler,
            residualRegistry: ResidualLeaseRegistry,
            release: () -> LeaseCleanupResult,
        ): LeaseCleanupBoundaryImpl =
            LeaseCleanupBoundaryImpl(scheduler, residualRegistry, release, null)

        operator fun invoke(
            scheduler: LeaseOperationScheduler,
            residualRegistry: ResidualLeaseRegistry,
            releaseWithin: (Long) -> LeaseCleanupResult,
        ): LeaseCleanupBoundaryImpl =
            LeaseCleanupBoundaryImpl(
                scheduler,
                residualRegistry,
                { releaseWithin(Long.MAX_VALUE) },
                releaseWithin
            )
    }

    @Suppress("ReturnCount")
    override fun releaseWithin(deadline: Duration, reservation: LeaseCleanupReservation): LeaseCleanupResult {
        if (reservation.isTerminal) return LeaseCleanupResult.NOT_HELD
        val remainingNanos = deadline.inWholeNanoseconds.coerceAtLeast(0L)
        if (remainingNanos == 0L) return transfer(reservation)
        val operationDeadline = System.nanoTime() safePlus remainingNanos

        val future = scheduler.submit {
            deadlineAwareRelease?.invoke(operationDeadline) ?: release()
        } ?: return transfer(reservation)

        return try {
            val result = future.get(remainingNanos.nanoseconds)
            when (result) {
                LeaseCleanupResult.RELEASED, LeaseCleanupResult.NOT_HELD -> {
                    reservation.terminalize()
                    result
                }

                LeaseCleanupResult.RESIDUAL_TRANSFERRED ->
                    transfer(reservation)
            }
        } catch (_: TimeoutException) {
            future.cancel(false)
            transfer(reservation)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            future.cancel(false)
            transfer(reservation)
        } catch (_: ExecutionException) {
            future.cancel(false)
            transfer(reservation)
        }
    }

    private fun transfer(reservation: LeaseCleanupReservation): LeaseCleanupResult {
        residualRegistry.transfer(reservation)
        return LeaseCleanupResult.RESIDUAL_TRANSFERRED
    }
}
