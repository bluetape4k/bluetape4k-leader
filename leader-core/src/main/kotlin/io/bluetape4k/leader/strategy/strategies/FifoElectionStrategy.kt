package io.bluetape4k.leader.strategy.strategies

import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.ElectionResult
import io.bluetape4k.leader.strategy.ElectionStrategy
import io.bluetape4k.leader.strategy.Elimination

/**
 * 가장 먼저 등록된 후보를 선택하며 등록 시각이 같으면 `nodeId` 사전순으로 승자를 결정합니다.
 */
object FifoElectionStrategy: ElectionStrategy {

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
