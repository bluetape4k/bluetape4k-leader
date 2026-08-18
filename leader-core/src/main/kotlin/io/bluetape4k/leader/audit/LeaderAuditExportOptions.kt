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

    private val validatedAttemptTimeoutNanos: Long =
        attemptTimeout.toAuditPositiveNanos("attemptTimeout", MAX_ATTEMPT_TIMEOUT_NANOS)

    private val validatedInitialBackoffNanos: Long =
        initialBackoff.toAuditPositiveNanos("initialBackoff", MAX_BACKOFF_NANOS)

    private val validatedMaxBackoffNanos: Long =
        maxBackoff.toAuditPositiveNanos("maxBackoff", MAX_BACKOFF_NANOS)

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
        require(validatedInitialBackoffNanos <= validatedMaxBackoffNanos) {
            "initialBackoff must be <= maxBackoff"
        }
    }

    companion object {
        internal const val MAX_QUEUE_CAPACITY: Int = 65_536
        internal const val MAX_ATTEMPTS: Int = 16
    }
}

internal val MAX_ATTEMPT_TIMEOUT_NANOS: Duration = Duration.ofMinutes(5)
internal val MAX_BACKOFF_NANOS: Duration = Duration.ofMinutes(1)

internal fun Duration.toAuditPositiveNanos(name: String, maximum: Duration): Long {
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
