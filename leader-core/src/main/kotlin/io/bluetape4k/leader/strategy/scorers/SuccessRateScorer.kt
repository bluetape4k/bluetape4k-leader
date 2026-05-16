package io.bluetape4k.leader.strategy.scorers

import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.CandidateScorer

/**
 * A [CandidateScorer] that assigns a higher score to nodes with a higher success rate.
 *
 * Nodes with no execution history are assigned a success rate of 0.0.
 * Designed for resilience: consistently stable nodes are preferred over unreliable ones.
 *
 * ## Example
 * ```kotlin
 * // Stability first: elect the node with the highest success rate
 * val strategy = ScoredElectionStrategy(SuccessRateScorer)
 * election.runIfLeader("payment-job", strategy) { processPayments() }
 * ```
 */
object SuccessRateScorer : CandidateScorer {

    override fun score(candidate: CandidateInfo, all: List<CandidateInfo>): Double =
        candidate.successRate * 100.0
}
