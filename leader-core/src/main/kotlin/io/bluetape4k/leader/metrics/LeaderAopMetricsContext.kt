package io.bluetape4k.leader.metrics

import io.bluetape4k.leader.identity.LeaderIdSource
import io.bluetape4k.support.requireNotBlank

/**
 * Context carrying the resolved leader identity for metrics recording.
 *
 * ## Variants
 * - [Unknown]: No identity available — recorded before election or on skip path.
 * - [Identified]: Election succeeded and a leader ID + source are known.
 *
 * ## Contract
 * - [Empty] is a Java-compatible alias for [Unknown].
 * - [Identified.leaderId] must be non-blank (enforced in `init`).
 *
 * ## Usage
 * ```kotlin
 * val ctx: LeaderAopMetricsContext = LeaderAopMetricsContext.Identified("node-a", LeaderIdSource.LITERAL)
 * recorder.onLockAcquired("my-lock", opts, elapsed, ctx)
 * ```
 */
sealed interface LeaderAopMetricsContext {

    /** No leader identity available. Used on the skip/pre-election path. */
    data object Unknown : LeaderAopMetricsContext

    /**
     * Leader identity is known after election.
     *
     * @property leaderId the resolved leader identity (non-blank)
     * @property leaderIdSource the provenance of [leaderId]
     */
    data class Identified(
        val leaderId: String,
        val leaderIdSource: LeaderIdSource,
    ) : LeaderAopMetricsContext {
        init {
            leaderId.requireNotBlank("leaderId")
        }
    }

    companion object {
        /** Java-compatible alias for [Unknown]. */
        @JvmField
        val Empty: LeaderAopMetricsContext = Unknown
    }
}
