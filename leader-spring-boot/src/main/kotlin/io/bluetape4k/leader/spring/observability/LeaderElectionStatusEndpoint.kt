package io.bluetape4k.leader.spring.observability

import io.bluetape4k.leader.LeaderElector
import org.springframework.boot.actuate.endpoint.annotation.Endpoint
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation
import java.io.Serializable
import java.time.Instant

/**
 * Actuator endpoint that exposes best-effort single-leader status for known locks.
 *
 * ## Behavior / Contract
 * - Lock names come from [LeaderElectionStatusRegistry].
 * - Each lock state is read from [LeaderElector.state] at request time.
 * - The response is JVM-local and should be used for diagnostics, not election decisions.
 *
 * ```json
 * {
 *   "locks": [
 *     {
 *       "name": "batch-job",
 *       "status": "Occupied",
 *       "leaderId": "node-1",
 *       "leaseExpiry": "2026-05-16T00:00:00Z"
 *     }
 *   ]
 * }
 * ```
 */
@Endpoint(id = "leaderElection")
class LeaderElectionStatusEndpoint(
    private val leaderElector: LeaderElector,
    private val registry: LeaderElectionStatusRegistry,
) {

    /**
     * Returns single-leader status for all known lock names.
     */
    @ReadOperation
    fun leaderElectionStatus(): LeaderElectionStatusResponse =
        LeaderElectionStatusResponse(
            locks = registry.snapshot().map { lockName ->
                val state = leaderElector.state(lockName)
                LeaderElectionLockStatus(
                    name = lockName,
                    status = state.status.name,
                    leaderId = state.leader?.auditLeaderId,
                    leaseExpiry = state.leader?.leaseUntil,
                )
            }
        )
}

/**
 * Actuator response body for the leader election endpoint.
 */
data class LeaderElectionStatusResponse(
    val locks: List<LeaderElectionLockStatus>,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * Single known lock status entry returned by [LeaderElectionStatusEndpoint].
 */
data class LeaderElectionLockStatus(
    val name: String,
    val status: String,
    val leaderId: String?,
    val leaseExpiry: Instant?,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
