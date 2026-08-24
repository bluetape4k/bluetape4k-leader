package io.bluetape4k.leader

import kotlin.time.Duration

/** bounded deadline cleanup을 위한 core boundary입니다. */
interface LeaseCleanupBoundary {
    /** cleanup reservation으로 fencing-aware release를 수행합니다. */
    fun releaseWithin(
        deadline: Duration,
        reservation: LeaseCleanupReservation,
    ): LeaseCleanupResult
}
