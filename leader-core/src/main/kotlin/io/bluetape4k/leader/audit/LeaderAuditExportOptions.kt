package io.bluetape4k.leader.audit

import io.bluetape4k.ToStringBuilder
import io.bluetape4k.javatimes.minutes
import io.bluetape4k.javatimes.requirePositiveDuration
import io.bluetape4k.support.hashOf
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireLe
import io.bluetape4k.support.requirePositiveNumber
import java.time.Duration
import java.util.concurrent.Executor
import java.util.concurrent.ScheduledExecutorService

/**
 * bounded exporter의 queue, retry, timeout과 caller-owned 실행기를 정의합니다.
 *
 * executor와 scheduler는 exporter가 소유하거나 종료하지 않습니다. exporter를 먼저
 * `close`한 뒤 caller가 실행기를 종료해야 합니다.
 */
class LeaderAuditExportOptions(
    val queueCapacity: Int,
    val maxInFlight: Int,
    val maxAttempts: Int,
    val attemptTimeout: Duration,
    val initialBackoff: Duration,
    val maxBackoff: Duration,
    val executor: Executor,
    val scheduler: ScheduledExecutorService,
) {
    companion object {
        private const val MAX_QUEUE_CAPACITY: Int = 65_536
        private const val MAX_ATTEMPTS: Int = 16
    }

    init {
        attemptTimeout.toAuditPositiveNanos("attemptTimeout", MAX_ATTEMPT_TIMEOUT_NANOS)
        val initialBackoffNanos = initialBackoff.toAuditPositiveNanos("initialBackoff", MAX_BACKOFF_NANOS)
        val maxBackoffNanos = maxBackoff.toAuditPositiveNanos("maxBackoff", MAX_BACKOFF_NANOS)
        queueCapacity.requireInRange(1, MAX_QUEUE_CAPACITY, "queueCapacity")
        maxInFlight.requireInRange(1, queueCapacity, "maxInFlight")
        maxAttempts.requireInRange(1, MAX_ATTEMPTS, "maxAttempts")
        initialBackoffNanos.requireLe(maxBackoffNanos, "initialBackoffNanos")
    }

    override fun equals(other: Any?): Boolean =
        other is LeaderAuditExportOptions &&
                queueCapacity == other.queueCapacity &&
                maxInFlight == other.maxInFlight &&
                maxAttempts == other.maxAttempts &&
                attemptTimeout == other.attemptTimeout &&
                initialBackoff == other.initialBackoff &&
                maxBackoff == other.maxBackoff

    override fun hashCode(): Int = hashOf(queueCapacity, maxInFlight, maxAttempts, attemptTimeout, initialBackoff)

    override fun toString(): String =
        ToStringBuilder(this)
            .add("queueCapacity", queueCapacity)
            .add("maxInFlight", maxInFlight)
            .add("maxAttempts", maxAttempts)
            .add("attemptTimeout", attemptTimeout)
            .add("initialBackoff", initialBackoff)
            .add("maxBackoff", maxBackoff)
            .toString()
}

private val MAX_ATTEMPT_TIMEOUT_NANOS: Duration = 5.minutes()
private val MAX_BACKOFF_NANOS: Duration = 1.minutes()

private fun Duration.toAuditPositiveNanos(name: String, maximum: Duration): Long {
    requirePositiveDuration(name)
    val nanos = try {
        toNanos()
    } catch (e: ArithmeticException) {
        throw IllegalArgumentException("$name does not fit in nanoseconds: $this", e)
    }
    nanos.requirePositiveNumber { "$name must be positive: $this" }
    val maximumNanos = maximum.toNanos()
    nanos.requireLe(maximumNanos) { "$name must be <= $maximum: $this" }

    return nanos
}
