package io.bluetape4k.leader.etcd.internal

import io.bluetape4k.ToStringBuilder
import io.bluetape4k.leader.utils.safePlus
import io.bluetape4k.logging.KLogging
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

internal class EtcdAcquisitionDeadline private constructor(
    private val deadlineNanos: Long,
) {

    companion object: KLogging() {
        fun fromNow(waitTime: Duration): EtcdAcquisitionDeadline {
            val budgetNanos = waitTime.inWholeNanoseconds.coerceAtLeast(1L)
            val now = System.nanoTime()
            val deadlineNanos = now safePlus budgetNanos
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
