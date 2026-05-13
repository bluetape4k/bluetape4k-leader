package io.bluetape4k.leader

import io.bluetape4k.leader.identity.LeaderIdProvider
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireNotBlank
import java.io.Serializable

/**
 * Identifies a specific leader slot: the combination of lock name and the elected node's audit identity.
 *
 * ## Contract
 * - [lockName] must be non-blank — used as the distributed lock key.
 * - [leaderId] must be non-blank — stamped into [LeaderLease.auditLeaderId] and [LeaderLockHandle.Real.auditLeaderId]
 *   for audit traceability.
 * - Both fields are immutable value-object semantics: equality is field-based.
 *
 * ## Usage
 * ```kotlin
 * val slot = LeaderSlot("daily-job", "node-a-x7f3")
 * val slotFromProvider = LeaderSlot.of("daily-job", RandomLeaderIdProvider.Default)
 * elector.runIfLeader(slot) { doWork() }
 * ```
 */
data class LeaderSlot(
    val lockName: String,
    val leaderId: String,
) : Serializable {

    init {
        lockName.requireNotBlank("lockName")
        require(lockName.none { it == '{' || it == '}' }) {
            "lockName must not contain '{' or '}' (Redis Cluster hashtag injection risk): '$lockName'"
        }
        require(lockName.none { it.code < 0x20 || it.code == 0x7F }) {
            "lockName must not contain control characters (log injection risk)"
        }
        leaderId.requireNotBlank("leaderId")
        require(leaderId.none { it.code < 0x20 || it.code == 0x7F }) {
            "leaderId must not contain control characters (log injection risk)"
        }
    }

    companion object : KLogging() {
        private const val serialVersionUID = 1L

        /**
         * Creates a [LeaderSlot] by generating a leader ID from the given [provider].
         *
         * @param lockName the distributed lock key (must be non-blank)
         * @param provider the [LeaderIdProvider] to generate a unique leader identity
         */
        fun of(lockName: String, provider: LeaderIdProvider): LeaderSlot =
            LeaderSlot(lockName, provider.nextLeaderId(lockName))
    }
}
