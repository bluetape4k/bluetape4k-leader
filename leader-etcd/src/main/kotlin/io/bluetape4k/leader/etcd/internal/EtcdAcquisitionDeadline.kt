package io.bluetape4k.leader.etcd.internal

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
            val deadlineNanos = if (Long.MAX_VALUE - now < budgetNanos) {
                Long.MAX_VALUE
            } else {
                now + budgetNanos
            }
            return EtcdAcquisitionDeadline(deadlineNanos)
        }
    }

    fun remainingMillis(): Long {
        val remainingNanos = deadlineNanos - System.nanoTime()
        return TimeUnit.NANOSECONDS.toMillis(remainingNanos).coerceAtLeast(1L)
    }

    fun remainingDuration(): Duration =
        remainingMillis().milliseconds
}
