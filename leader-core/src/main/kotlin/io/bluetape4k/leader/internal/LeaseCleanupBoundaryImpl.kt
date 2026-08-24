package io.bluetape4k.leader.internal

import io.bluetape4k.leader.LeaseCleanupBoundary
import io.bluetape4k.leader.LeaseCleanupReservation
import io.bluetape4k.leader.LeaseCleanupResult
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

/** deadline을 인식하고 scheduler/backend timeout 시 residual로 넘기는 cleanup 구현입니다. */
class LeaseCleanupBoundaryImpl private constructor(
    private val scheduler: LeaseOperationScheduler,
    private val residualRegistry: ResidualLeaseRegistry,
    private val release: () -> LeaseCleanupResult,
    private val deadlineAwareRelease: ((Long) -> LeaseCleanupResult)?,
) : LeaseCleanupBoundary {

    constructor(
        scheduler: LeaseOperationScheduler,
        residualRegistry: ResidualLeaseRegistry,
        release: () -> LeaseCleanupResult,
    ) : this(scheduler, residualRegistry, release, null)

    constructor(
        scheduler: LeaseOperationScheduler,
        residualRegistry: ResidualLeaseRegistry,
        releaseWithin: (Long) -> LeaseCleanupResult,
    ) : this(scheduler, residualRegistry, { releaseWithin(Long.MAX_VALUE) }, releaseWithin)

    @Suppress("ReturnCount")
    override fun releaseWithin(deadline: Duration, reservation: LeaseCleanupReservation): LeaseCleanupResult {
        if (reservation.isTerminal) return LeaseCleanupResult.NOT_HELD
        val remainingNanos = deadline.inWholeNanoseconds.coerceAtLeast(0L)
        if (remainingNanos == 0L) return transfer(reservation)
        val operationDeadline = safePlus(System.nanoTime(), remainingNanos)
        val future = scheduler.submit {
            deadlineAwareRelease?.invoke(operationDeadline) ?: release()
        } ?: return transfer(reservation)
        return try {
            val result = future.get(remainingNanos, TimeUnit.NANOSECONDS)
            when (result) {
                LeaseCleanupResult.RELEASED, LeaseCleanupResult.NOT_HELD -> {
                    reservation.terminalize()
                    result
                }

                LeaseCleanupResult.RESIDUAL_TRANSFERRED -> transfer(reservation)
            }
        } catch (_: java.util.concurrent.TimeoutException) {
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

    private fun safePlus(first: Long, second: Long): Long =
        if (second > 0 && first > Long.MAX_VALUE - second) Long.MAX_VALUE else first + second
}
