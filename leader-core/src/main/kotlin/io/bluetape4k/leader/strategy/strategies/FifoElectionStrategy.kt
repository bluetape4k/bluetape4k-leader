package io.bluetape4k.leader.strategy.strategies

import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.ElectionResult
import io.bluetape4k.leader.strategy.ElectionStrategy
import io.bluetape4k.leader.strategy.Elimination

/**
 * 가장 먼저 등록된 후보를 리더로 선출하는 FIFO 전략입니다.
 *
 * 동점(registeredAt 동일) 시 [CandidateInfo.nodeId] 사전순으로 결정합니다.
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
                    "등록 시각 늦음 (${c.registeredAt} > ${winner.registeredAt})"
                } else {
                    "nodeId 사전순 뒤 (${c.nodeId} > ${winner.nodeId})"
                }
                Elimination(c, reason)
            }
        return ElectionResult(winner, eliminations)
    }
}
