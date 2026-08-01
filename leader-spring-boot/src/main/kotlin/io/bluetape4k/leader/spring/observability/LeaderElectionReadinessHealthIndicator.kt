package io.bluetape4k.leader.spring.observability

import io.bluetape4k.leader.LeaderElectionState
import org.springframework.boot.health.contributor.AbstractHealthIndicator
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.Status
import java.time.Clock
import java.time.Duration

/**
 * `LeaderElectionReadinessHealthIndicator`는 Spring Boot integration의 leader election,
 * route guard, metric, example workflow 계약을 설명합니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
 * @property selectedState 관측에 사용할 backend와 상태 제공자입니다.
 * @property registry Spring Boot integration 계약에서 사용하는 속성입니다.
 * @property leaseWarningThreshold Spring Boot integration 계약에서 사용하는 속성입니다.
 * @property clock Spring Boot integration 계약에서 사용하는 속성입니다.
 */
class LeaderElectionReadinessHealthIndicator(
    leaderElector: LeaderElectionState,
    private val registry: LeaderElectionStatusRegistry,
    private val leaseWarningThreshold: Duration,
    private val clock: Clock = Clock.systemUTC(),
) : AbstractHealthIndicator("Leader election readiness check failed") {

    private val stateProvider: LeaderElectionState = leaderElector
    private val selectedBackend: String =
        (stateProvider as? SelectedStateProvider)?.backendName ?: "unknown"
    private val selectedStateProviderBean: String =
        (stateProvider as? SelectedStateProvider)?.beanName.orEmpty()

    init {
        require(!leaseWarningThreshold.isNegative) {
            "leaseWarningThreshold must not be negative: $leaseWarningThreshold"
        }
    }

    override fun doHealthCheck(builder: Health.Builder) {
        val lockNames = registry.snapshot()
        if (!stateProvider.supportsAuditLeaderState) {
            builder
                .status(Status.UNKNOWN)
                .withDetails(baseDetails(lockNames.size) + mapOf(DETAIL_STATE_SUPPORTED to false))
            return
        }
        val warningBoundary = clock.instant().plus(leaseWarningThreshold)
        var occupiedLocks = 0
        var unknownLeaseExpiry = 0
        val expiringLockNames = mutableListOf<String>()
        val failedLockNames = mutableListOf<String>()

        lockNames.forEach { lockName ->
            try {
                val state = stateProvider.state(lockName)
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
            baseDetails(lockNames.size) + mapOf(
                DETAIL_STATE_SUPPORTED to true,
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

    private fun baseDetails(knownLocks: Int): Map<String, Any> =
        mapOf(
            DETAIL_BACKEND to selectedBackend,
            DETAIL_STATE_PROVIDER_BEAN to selectedStateProviderBean,
            DETAIL_KNOWN_LOCKS to knownLocks,
            DETAIL_OCCUPIED_LOCKS to 0,
            DETAIL_UNKNOWN_LEASE_EXPIRY to 0,
            DETAIL_EXPIRING_LEASES to 0,
            DETAIL_EXPIRING_LOCK_NAMES to emptyList<String>(),
            DETAIL_FAILED_LOCK_NAMES to emptyList<String>(),
        )

    companion object {
        @JvmSynthetic
        @Suppress("LongParameterList")
        internal fun fromSelectedState(
            backendName: String,
            stateProviderBean: String,
            state: LeaderElectionState,
            registry: LeaderElectionStatusRegistry,
            leaseWarningThreshold: Duration,
            clock: Clock = Clock.systemUTC(),
        ): LeaderElectionReadinessHealthIndicator = LeaderElectionReadinessHealthIndicator(
            leaderElector = SelectedStateProvider(backendName, stateProviderBean, state),
            registry = registry,
            leaseWarningThreshold = leaseWarningThreshold,
            clock = clock,
        )

        private const val DETAIL_BACKEND = "backend"
        private const val DETAIL_STATE_PROVIDER_BEAN = "stateProviderBean"
        private const val DETAIL_STATE_SUPPORTED = "stateSupported"
        private const val DETAIL_KNOWN_LOCKS = "knownLocks"
        private const val DETAIL_OCCUPIED_LOCKS = "occupiedLocks"
        private const val DETAIL_UNKNOWN_LEASE_EXPIRY = "unknownLeaseExpiry"
        private const val DETAIL_EXPIRING_LEASES = "expiringLeases"
        private const val DETAIL_EXPIRING_LOCK_NAMES = "expiringLockNames"
        private const val DETAIL_FAILED_LOCK_NAMES = "failedLockNames"
    }
}

/** Internal adapter that keeps selector details out of the public health-indicator constructor. */
private class SelectedStateProvider(
    val backendName: String,
    val beanName: String,
    private val delegate: LeaderElectionState,
) : LeaderElectionState {
    override val supportsAuditLeaderState: Boolean
        get() = delegate.supportsAuditLeaderState

    override fun state(lockName: String) = delegate.state(lockName)
}
