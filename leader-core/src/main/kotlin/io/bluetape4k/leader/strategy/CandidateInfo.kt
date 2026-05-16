package io.bluetape4k.leader.strategy

import java.io.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import java.time.Instant

/**
 * Data class holding metadata for a leader election candidate node.
 *
 * [idleDuration] represents the time elapsed since the last completion.
 * If there is no execution history, it is calculated from [registeredAt].
 *
 * @property nodeId node identifier. Should be assigned as UUID v7 at node instance creation and reused.
 * @property registeredAt time when the candidate was registered
 * @property lastStartTime time of the last action start
 * @property lastCompletionTime time of the last action completion
 * @property successCount cumulative success count
 * @property failureCount cumulative failure count
 * @property metadata extensible metadata
 */
data class CandidateInfo(
    val nodeId: String,
    val registeredAt: Instant = Instant.now(),
    val lastStartTime: Instant? = null,
    val lastCompletionTime: Instant? = null,
    val successCount: Long = 0L,
    val failureCount: Long = 0L,
    val metadata: Map<String, String> = emptyMap(),
) : Serializable {

    /**
     * Time elapsed since the last completion.
     * If there is no completion history, calculated from [registeredAt] (unrun node = total time since registration).
     */
    val idleDuration: Duration
        get() = lastCompletionTime?.let { (Instant.now().toEpochMilli() - it.toEpochMilli()).milliseconds }
            ?: (Instant.now().toEpochMilli() - registeredAt.toEpochMilli()).milliseconds

    /** Success rate (0.0 to 1.0). Returns 0.0 if there is no history. */
    val successRate: Double
        get() = if (successCount + failureCount == 0L) 0.0
                else successCount.toDouble() / (successCount + failureCount)

    /** Total execution count. */
    val totalCount: Long get() = successCount + failureCount

    /**
     * Returns a new [CandidateInfo] with the action result applied.
     */
    fun withResult(result: CandidateResult, completionTime: Instant = Instant.now()): CandidateInfo =
        when (result) {
            CandidateResult.SUCCESS -> copy(
                lastCompletionTime = completionTime,
                successCount = successCount + 1,
            )
            CandidateResult.FAILURE -> copy(
                lastCompletionTime = completionTime,
                failureCount = failureCount + 1,
            )
        }
}
