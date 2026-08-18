package io.bluetape4k.leader.audit

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

    /** 검증된 nanosecond timeout입니다. */
    internal val attemptTimeoutNanos: Long = attemptTimeout.toPositiveNanos("attemptTimeout", MAX_ATTEMPT_TIMEOUT)

    /** 검증된 nanosecond initial backoff입니다. */
    internal val initialBackoffNanos: Long = initialBackoff.toPositiveNanos("initialBackoff", MAX_BACKOFF)

    /** 검증된 nanosecond maximum backoff입니다. */
    internal val maxBackoffNanos: Long = maxBackoff.toPositiveNanos("maxBackoff", MAX_BACKOFF)

    init {
        require(queueCapacity in 1..MAX_QUEUE_CAPACITY) {
            "queueCapacity must be in 1..$MAX_QUEUE_CAPACITY: $queueCapacity"
        }
        require(maxInFlight in 1..queueCapacity) {
            "maxInFlight must be in 1..queueCapacity: $maxInFlight"
        }
        require(maxAttempts in 1..MAX_ATTEMPTS) {
            "maxAttempts must be in 1..$MAX_ATTEMPTS: $maxAttempts"
        }
        require(initialBackoffNanos <= maxBackoffNanos) {
            "initialBackoff must be <= maxBackoff"
        }
    }

    companion object {
        internal const val MAX_QUEUE_CAPACITY: Int = 65_536
        internal const val MAX_ATTEMPTS: Int = 16
        private val MAX_ATTEMPT_TIMEOUT: Duration = Duration.ofMinutes(5)
        private val MAX_BACKOFF: Duration = Duration.ofMinutes(1)
    }
}

private fun Duration.toPositiveNanos(name: String, maximum: Duration): Long {
    require(!isZero && !isNegative) { "$name must be positive: $this" }
    val nanos = try {
        toNanos()
    } catch (e: ArithmeticException) {
        throw IllegalArgumentException("$name does not fit in nanoseconds: $this", e)
    }
    require(nanos > 0) { "$name must be positive: $this" }
    val maximumNanos = maximum.toNanos()
    require(nanos <= maximumNanos) { "$name must be <= $maximum: $this" }
    return nanos
}
