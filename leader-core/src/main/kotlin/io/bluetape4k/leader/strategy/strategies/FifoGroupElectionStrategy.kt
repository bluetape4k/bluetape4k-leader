package io.bluetape4k.leader.strategy.strategies

import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.Elimination
import io.bluetape4k.leader.strategy.GroupElectionStrategy
import io.bluetape4k.leader.strategy.StrategicGroupElectionResult
import io.bluetape4k.support.requireGe

/**
 * `FifoGroupElectionStrategy`는 등록 순서가 빠른 후보부터 최대 N개를 선택합니다.
 *
 * `registeredAt`이 같으면 `nodeId` 사전순으로 결과를 고정합니다.
 */
object FifoGroupElectionStrategy : GroupElectionStrategy {

    override fun elect(
        candidates: List<CandidateInfo>,
        maxLeaders: Int,
    ): StrategicGroupElectionResult {
        maxLeaders.requireGe(1, "maxLeaders")
        if (candidates.isEmpty()) return StrategicGroupElectionResult.EMPTY

        val ordered = candidates.sortedWith(
            compareBy(CandidateInfo::registeredAt).thenBy(CandidateInfo::nodeId)
        )
        val winners = ordered.take(maxLeaders)
        val boundary = winners.last()
        val eliminations = ordered.drop(maxLeaders).map { candidate ->
            val reason = if (candidate.registeredAt > boundary.registeredAt) {
                "registered later (${candidate.registeredAt} > ${boundary.registeredAt})"
            } else {
                "nodeId lexicographically after boundary (${candidate.nodeId} > ${boundary.nodeId})"
            }
            Elimination(candidate, reason)
        }
        return StrategicGroupElectionResult(winners, eliminations)
    }
}
