package io.bluetape4k.leader.spring.properties

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.support.requireGe
import io.bluetape4k.support.requireGt
import io.bluetape4k.support.requireLe
import io.bluetape4k.support.requireInRange
import java.io.Serializable
import java.time.Duration

private val DEFAULT_MAX_BLOCKING_WAIT_TIME: Duration = Duration.ofSeconds(5)
private val DEFAULT_MAX_LEASE_LIFETIME: Duration = Duration.ofMinutes(10)
private val DEFAULT_MINIMUM_AUTO_EXTEND_LEASE_TIME: Duration = Duration.ofMillis(100)
private val DEFAULT_MAX_EXPECTED_EXTENSION_LATENCY: Duration = Duration.ofMillis(50)
private val DEFAULT_DRAIN_TIMEOUT: Duration = Duration.ofSeconds(30)

/**
 * 요청별 lease route가 사용할 bounded admission, lifetime, cleanup 상한입니다.
 *
 * 이 값들은 요청 수나 lock identity에 따라 커지지 않는 고정 용량으로만 사용됩니다.
 * `effectiveActiveCapacity`는 설정에 다시 바인딩되지 않는 파생값입니다.
 */
data class LeaderRouteLeaseProperties(
    val maxBlockingWaitTime: Duration = DEFAULT_MAX_BLOCKING_WAIT_TIME,
    val maxConcurrentAcquires: Int = 256,
    val maxConcurrentCleanups: Int = 256,
    val maxAcquireQueueDepth: Int = 1_024,
    val maxCleanupQueueDepth: Int = 1_024,
    val maxMvcBlockingAcquires: Int = 32,
    val maxActiveLeases: Int = 10_000,
    val maxResidualLeases: Int = 1_024,
    val maxWatchdogInFlight: Int = 256,
    val maxLeaseLifetime: Duration = DEFAULT_MAX_LEASE_LIFETIME,
    val minimumAutoExtendLeaseTime: Duration = DEFAULT_MINIMUM_AUTO_EXTEND_LEASE_TIME,
    val maxExpectedExtensionLatency: Duration = DEFAULT_MAX_EXPECTED_EXTENSION_LATENCY,
    val drainTimeout: Duration = DEFAULT_DRAIN_TIMEOUT,
) : Serializable {

    /** residual admission과 active admission이 함께 허용하는 최대 동시 lease 수입니다. */
    val effectiveActiveCapacity: Int
        get() = minOf(maxActiveLeases, maxResidualLeases)

    /**
     * LEASE mode가 실제로 선택될 때만 bounded semantic contract를 검증합니다.
     * STATE/CUSTOM 또는 비활성 route guard에서는 nested lease 값을 읽기만 하므로
     * 해당 모드의 startup을 lease 전용 설정 오류가 막지 않습니다.
     */
    internal fun validateForLeaseMode(enforceTimingBudget: Boolean = true) {
        requirePositive(maxBlockingWaitTime, "maxBlockingWaitTime")
        requireBounded(maxConcurrentAcquires, 1..MAX_CONCURRENT, "maxConcurrentAcquires")
        requireBounded(maxConcurrentCleanups, 1..MAX_CONCURRENT, "maxConcurrentCleanups")
        requireBounded(maxAcquireQueueDepth, 1..MAX_QUEUE_DEPTH, "maxAcquireQueueDepth")
        requireBounded(maxCleanupQueueDepth, 1..MAX_QUEUE_DEPTH, "maxCleanupQueueDepth")
        requireBounded(maxMvcBlockingAcquires, 1..maxConcurrentAcquires, "maxMvcBlockingAcquires")
        requireBounded(maxActiveLeases, 1..MAX_ACTIVE_LEASES, "maxActiveLeases")
        requireBounded(maxResidualLeases, 1..maxActiveLeases, "maxResidualLeases")
        requireBounded(maxWatchdogInFlight, 1..MAX_ACTIVE_LEASES, "maxWatchdogInFlight")
        requirePositive(maxLeaseLifetime, "maxLeaseLifetime")
        maxBlockingWaitTime.requireLe(MAX_BLOCKING_WAIT_TIME, "maxBlockingWaitTime")
        maxLeaseLifetime.requireLe(MAX_LEASE_LIFETIME, "maxLeaseLifetime")
        maxBlockingWaitTime.requireLe(maxLeaseLifetime, "maxBlockingWaitTime")
        requirePositive(minimumAutoExtendLeaseTime, "minimumAutoExtendLeaseTime")
        requirePositive(maxExpectedExtensionLatency, "maxExpectedExtensionLatency")
        if (enforceTimingBudget) {
            minimumAutoExtendLeaseTime.requireLe(maxLeaseLifetime, "minimumAutoExtendLeaseTime")
            maxExpectedExtensionLatency.toNanos().requireLe(
                maxLeaseLifetime.toNanos() / EXTENSION_LATENCY_BUDGET_DIVISOR,
                "maxExpectedExtensionLatency nanos",
            )
        }
        maxExpectedExtensionLatency.requireLe(minimumAutoExtendLeaseTime, "maxExpectedExtensionLatency")
        requirePositive(drainTimeout, "drainTimeout")
        drainTimeout.requireLe(MAX_DRAIN_TIMEOUT, "drainTimeout")
    }

    /** 선택된 elector의 불변 옵션 baseline과 route safety cap을 함께 검증합니다. */
    internal fun validateAgainst(options: LeaderElectionOptions) {
        options.waitTime.inWholeNanoseconds.requireLe(maxBlockingWaitTime.toNanos(), "elector waitTime")
        options.leaseTime.inWholeNanoseconds.requireLe(maxLeaseLifetime.toNanos(), "elector leaseTime")
        if (options.autoExtend) {
            options.leaseTime.inWholeNanoseconds.requireGe(
                minimumAutoExtendLeaseTime.toNanos(),
                "auto-extend elector leaseTime",
            )
            options.leaseTime.inWholeNanoseconds.requireGe(
                maxExpectedExtensionLatency.toNanos() * EXTENSION_LATENCY_BUDGET_DIVISOR,
                "auto-extend elector leaseTime",
            )
        }
    }

    private fun requirePositive(value: Duration, name: String) {
        value.requireGt(Duration.ZERO, name)
    }

    private fun requireBounded(value: Int, range: IntRange, name: String) {
        value.requireInRange(range.first, range.last, name)
    }

    companion object {
        private const val serialVersionUID = 1L
        private const val EXTENSION_LATENCY_BUDGET_DIVISOR = 3L
        private const val MAX_CONCURRENT = 4_096
        private const val MAX_QUEUE_DEPTH = 65_536
        private const val MAX_ACTIVE_LEASES = 65_536
        private val MAX_DRAIN_TIMEOUT: Duration = Duration.ofMinutes(10)
        private val MAX_BLOCKING_WAIT_TIME: Duration = Duration.ofMinutes(5)
        private val MAX_LEASE_LIFETIME: Duration = Duration.ofHours(24)
    }
}
