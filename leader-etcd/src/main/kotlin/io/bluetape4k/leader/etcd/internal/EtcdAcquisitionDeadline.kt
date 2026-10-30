package io.bluetape4k.leader.etcd.internal

import io.bluetape4k.ToStringBuilder
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

internal class EtcdAcquisitionDeadline private constructor(
    private val deadlineNanos: Long,
) {

    companion object {
        fun fromNow(waitTime: Duration): EtcdAcquisitionDeadline {
            val budgetNanos = waitTime.inWholeNanoseconds.coerceAtLeast(1L)
            val now = System.nanoTime()
            val deadlineNanos = now.safePlus(budgetNanos)
            return EtcdAcquisitionDeadline(deadlineNanos)
        }
    }

    fun remainingMillis(): Long {
        val remainingNanos = deadlineNanos - System.nanoTime()
        return TimeUnit.NANOSECONDS.toMillis(remainingNanos).coerceAtLeast(1L)
    }

    fun remainingDuration(): Duration =
        remainingMillis().milliseconds

    override fun toString(): String =
        ToStringBuilder(this)
            .add("deadlineNanos", deadlineNanos)
            .toString()
}

private infix fun Long.safePlus(other: Long): Long =
    if (other > 0L && this > Long.MAX_VALUE - other) Long.MAX_VALUE else this + other
