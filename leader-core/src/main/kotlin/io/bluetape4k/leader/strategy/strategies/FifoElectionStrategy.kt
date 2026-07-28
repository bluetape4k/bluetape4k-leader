package io.bluetape4k.leader.strategy.strategies

import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.ElectionResult
import io.bluetape4k.leader.strategy.ElectionStrategy
import io.bluetape4k.leader.strategy.Elimination

/**
 * `FifoElectionStrategy`는 전략 기반 leader 선출에서 후보 평가 규칙을 제공합니다.
 *
 * API 이름과 `lock`, `lease`, `leader`, `slot`, `audit` 용어는 코드 계약과 동일하게 유지합니다.
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
