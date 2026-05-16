package io.bluetape4k.leader.strategy.strategies

import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.ElectionResult
import io.bluetape4k.leader.strategy.ElectionStrategy
import io.bluetape4k.leader.strategy.Elimination

/**
 * An [ElectionStrategy] that elects the earliest-registered candidate as leader (FIFO order).
 *
 * ## Behavior / Contract
 * - When two candidates share the same [CandidateInfo.registeredAt], the one with the
 *   lexicographically earlier [CandidateInfo.nodeId] wins.
 */
object FifoElectionStrategy : ElectionStrategy {

    override fun elect(candidates: List<CandidateInfo>): ElectionResult {
        if (candidates.isEmpty()) return ElectionResult.EMPTY
        val winner = candidates.minWith(
            compareBy(CandidateInfo::registeredAt).thenBy(CandidateInfo::nodeId)
        )
        val eliminations = candidates
            .filter { it.nodeId != winner.nodeId }
            .map { c ->
                val reason = if (c.registeredAt > winner.registeredAt) {
                    "registered later (${c.registeredAt} > ${winner.registeredAt})"
                } else {
                    "nodeId lexicographically after winner (${c.nodeId} > ${winner.nodeId})"
                }
                Elimination(c, reason)
            }
        return ElectionResult(winner, eliminations)
    }
}
