package io.bluetape4k.leader.spring.observability

import io.bluetape4k.leader.LeaderElector
import org.springframework.boot.health.contributor.AbstractHealthIndicator
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.Status
import java.time.Clock
import java.time.Duration

/**
 * Opt-in readiness contributor for lock names known to this JVM.
 *
 * This indicator performs one best-effort [LeaderElector.state] read per registered lock. It does
 * not enumerate backend locks or mutate election state, and its result must not be used as an
 * ownership decision.
 */
class LeaderElectionReadinessHealthIndicator(
    private val leaderElector: LeaderElector,
    private val registry: LeaderElectionStatusRegistry,
    private val leaseWarningThreshold: Duration,
    private val clock: Clock = Clock.systemUTC(),
) : AbstractHealthIndicator("Leader election readiness check failed") {

    init {
        require(!leaseWarningThreshold.isNegative) {
            "leaseWarningThreshold must not be negative: $leaseWarningThreshold"
        }
    }

    override fun doHealthCheck(builder: Health.Builder) {
        val lockNames = registry.snapshot()
        val warningBoundary = clock.instant().plus(leaseWarningThreshold)
        var occupiedLocks = 0
        var unknownLeaseExpiry = 0
        val expiringLockNames = mutableListOf<String>()
        val failedLockNames = mutableListOf<String>()

        lockNames.forEach { lockName ->
            try {
                val state = leaderElector.state(lockName)
                if (state.isOccupied) {
                    occupiedLocks++
                    val leaseUntil = state.leader?.leaseUntil
                    when {
                        leaseUntil == null -> unknownLeaseExpiry++
                        !leaseUntil.isAfter(warningBoundary) -> expiringLockNames += lockName
                    }
                }
            } catch (_: Exception) {
                failedLockNames += lockName
            }
        }

        builder.withDetails(
            mapOf(
                DETAIL_KNOWN_LOCKS to lockNames.size,
                DETAIL_OCCUPIED_LOCKS to occupiedLocks,
                DETAIL_UNKNOWN_LEASE_EXPIRY to unknownLeaseExpiry,
                DETAIL_EXPIRING_LEASES to expiringLockNames.size,
                DETAIL_EXPIRING_LOCK_NAMES to expiringLockNames,
                DETAIL_FAILED_LOCK_NAMES to failedLockNames,
            )
        )

        when {
            failedLockNames.isNotEmpty() -> builder.down()
            expiringLockNames.isNotEmpty() -> builder.status(Status.OUT_OF_SERVICE)
            else -> builder.up()
        }
    }

    private companion object {
        const val DETAIL_KNOWN_LOCKS = "knownLocks"
        const val DETAIL_OCCUPIED_LOCKS = "occupiedLocks"
        const val DETAIL_UNKNOWN_LEASE_EXPIRY = "unknownLeaseExpiry"
        const val DETAIL_EXPIRING_LEASES = "expiringLeases"
        const val DETAIL_EXPIRING_LOCK_NAMES = "expiringLockNames"
        const val DETAIL_FAILED_LOCK_NAMES = "failedLockNames"
    }
}
