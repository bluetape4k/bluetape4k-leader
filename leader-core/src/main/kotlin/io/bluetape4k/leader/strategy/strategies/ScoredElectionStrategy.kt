package io.bluetape4k.leader.strategy.strategies

import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.CandidateScorer
import io.bluetape4k.leader.strategy.ElectionStrategy

/**
 * [CandidateScorer] 로 계산한 점수가 가장 높은 후보를 선출하는 전략입니다.
 *
 * 동점 시 [CandidateInfo.registeredAt] 오름차순 → [CandidateInfo.nodeId] 사전순으로 결정합니다.
 *
 * @property scorer 후보 점수 계산에 사용할 [CandidateScorer]
 */
class ScoredElectionStrategy(val scorer: CandidateScorer) : ElectionStrategy {

    override fun selectLeader(candidates: List<CandidateInfo>): CandidateInfo? {
        if (candidates.isEmpty()) return null
        val scores = candidates.associateWith { scorer.score(it, candidates) }
        val maxScore = scores.values.max()
        val topCandidates = candidates.filter { scores[it] == maxScore }
        return topCandidates.minWithOrNull(
            compareBy(CandidateInfo::registeredAt).thenBy(CandidateInfo::nodeId)
        )
    }
}
