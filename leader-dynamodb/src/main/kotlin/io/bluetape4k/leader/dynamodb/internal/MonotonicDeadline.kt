package io.bluetape4k.leader.dynamodb.internal

import io.bluetape4k.ToStringBuilder
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.requirePositiveNumber
import kotlin.time.Duration
import io.bluetape4k.leader.internal.MonotonicDeadline as CoreMonotonicDeadline

internal class MonotonicDeadline private constructor(
    private val deadlineNanos: Long,
    private val ticker: () -> Long,
) {
    companion object: KLogging() {
        fun fromNow(waitTime: Duration, ticker: () -> Long = System::nanoTime): MonotonicDeadline {
            val startNanos = ticker()
            val timeoutNanos = waitTime.inWholeNanoseconds.coerceAtLeast(0L)
            log.debug { "Create MonotonicDeadline. startNanos = $startNanos, timeoutNanos = $timeoutNanos" }
            return MonotonicDeadline(startNanos, ticker).withTimeout(timeoutNanos)
        }
    }

    private var delegate = CoreMonotonicDeadline.fromStart(deadlineNanos, 0L, ticker)

    private fun withTimeout(timeoutNanos: Long): MonotonicDeadline {
        delegate = CoreMonotonicDeadline.fromStart(deadlineNanos, timeoutNanos, ticker)
        return this
    }

    fun remainingNanos(): Long = delegate.remainingNanos()

    fun hasTimeRemaining(): Boolean = delegate.hasTimeRemaining()

    fun remainingMillisForDelay(maxDelayMillis: Long): Long {
        maxDelayMillis.requirePositiveNumber("maxDelayMillis")
        return delegate.remainingMillisForDelay(maxDelayMillis)
    }

    override fun toString(): String {
        return ToStringBuilder(this)
            .add("deadlineNanos", deadlineNanos)
            .toString()
    }
}
