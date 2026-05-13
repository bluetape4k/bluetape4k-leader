package io.bluetape4k.leader

import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireNotBlank
import java.io.Serializable
import java.time.Instant

/**
 * Snapshot of the lease held by the elected leader node or slot occupant.
 *
 * ## Contract
 * - [auditLeaderId] is the audit identity stamped at election time. This may be a node identifier,
 *   a fencing token, or a backend holder id depending on the backend's capabilities.
 * - [nodeId] is the physical node identity, when the backend tracks it separately.
 *   If the backend does not support physical node identity, this is `null`.
 * - [electedAt] and [leaseUntil] are best-effort values filled in by the backend where available.
 * - [slot] is only used in group leader election; `null` for single-leader election.
 *
 * ## Semantic Note — Fencing Token Regression Warning
 * Prior to this version, [leaderId] carried the fencing token (or backend-issued holder id) directly.
 * Starting from this version, [auditLeaderId] carries that audit identity and [nodeId] carries the
 * physical node. Consumers that used [leaderId] for fencing MUST migrate to [auditLeaderId] to
 * preserve fencing-token semantics. Mixing [nodeId] (physical) and [auditLeaderId] (token) in a
 * fencing comparison is a split-brain risk.
 *
 * @property auditLeaderId the audit identity of the elected leader (fencing token or backend holder id).
 * @property electedAt the instant at which the leader was elected; `null` if unavailable.
 * @property leaseUntil the instant until which the lease is valid; `null` if unavailable.
 * @property slot slot index in group election; `null` for single-leader election.
 * @property nodeId the physical node identity of the elected leader; `null` if unavailable.
 * @property leaderId deprecated accessor — returns [nodeId] ?: [auditLeaderId].
 *
 * ```kotlin
 * val lease = LeaderLease(
 *     auditLeaderId = "node-a",
 *     electedAt = Instant.now(),
 * )
 * ```
 */
data class LeaderLease(
    val auditLeaderId: String,
    val electedAt: Instant? = null,
    val leaseUntil: Instant? = null,
    val slot: Int? = null,
    val nodeId: String? = null,
) : Serializable {

    companion object : KLogging() {
        private const val serialVersionUID = 2L
    }

    init {
        auditLeaderId.requireNotBlank("auditLeaderId")
        nodeId?.requireNotBlank("nodeId")
        require(slot == null || slot >= 0) { "slot must be null or non-negative: $slot" }
        if (electedAt != null && leaseUntil != null) {
            require(!leaseUntil.isBefore(electedAt)) {
                "leaseUntil must not be before electedAt: electedAt=$electedAt, leaseUntil=$leaseUntil"
            }
        }
    }

    @Deprecated(
        message = "Use auditLeaderId for the elected node's audit identity, or nodeId for the physical node identity.",
        replaceWith = ReplaceWith("nodeId ?: auditLeaderId"),
    )
    val leaderId: String get() = nodeId ?: auditLeaderId
}
