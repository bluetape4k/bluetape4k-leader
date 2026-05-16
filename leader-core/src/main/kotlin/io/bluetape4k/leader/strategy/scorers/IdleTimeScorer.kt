package io.bluetape4k.leader.strategy.scorers

import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.CandidateScorer

/**
 * A [CandidateScorer] that assigns a higher score to the node that has been idle the longest
 * since its last task completion.
 *
 * Nodes with no execution history are measured from [CandidateInfo.registeredAt].
 * Prevents a single node from monopolizing work and distributes load across the pool.
 *
 * ## Behavior / Contract
 * - Score is normalized to 0.0–100.0 based on the maximum idle duration in the candidate pool,
 *   making it composable with other scorers in a [WeightedScorer].
 *
 * ## Example
 * ```kotlin
 * val strategy = ScoredElectionStrategy(IdleTimeScorer)
 * election.runIfLeader("rotating-batch", strategy) { processBatch() }
 * ```
 */
object IdleTimeScorer : CandidateScorer {

    override fun score(candidate: CandidateInfo, all: List<CandidateInfo>): Double {
        if (all.isEmpty()) return 0.0
        val maxIdleMillis = all.maxOf { it.idleDuration.inWholeMilliseconds }
        if (maxIdleMillis == 0L) return 0.0
        return candidate.idleDuration.inWholeMilliseconds.toDouble() / maxIdleMillis * 100.0
    }
}
