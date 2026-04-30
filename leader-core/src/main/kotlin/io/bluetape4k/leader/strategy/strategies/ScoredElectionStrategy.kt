package io.bluetape4k.leader.strategy.strategies

import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.CandidateScorer
import io.bluetape4k.leader.strategy.ElectionResult
import io.bluetape4k.leader.strategy.ElectionStrategy
import io.bluetape4k.leader.strategy.Elimination

/**
 * [CandidateScorer] 로 계산한 점수가 가장 높은 후보를 선출하는 전략입니다.
 *
 * 동점 시 [CandidateInfo.registeredAt] 오름차순 → [CandidateInfo.nodeId] 사전순으로 결정합니다.
 *
 * @property scorer 후보 점수 계산에 사용할 [CandidateScorer]
 */
class ScoredElectionStrategy(val scorer: CandidateScorer) : ElectionStrategy {

    override fun elect(candidates: List<CandidateInfo>): ElectionResult {
        if (candidates.isEmpty()) return ElectionResult.EMPTY
        val scores = candidates.associateWith { scorer.score(it, candidates) }
        val maxScore = scores.values.max()
        val topCandidates = candidates.filter { scores[it] == maxScore }
        val winner = topCandidates.minWith(
            compareBy(CandidateInfo::registeredAt).thenBy(CandidateInfo::nodeId)
        )
        val winnerScore = scores.getValue(winner)
        val eliminations = candidates
            .filter { it.nodeId != winner.nodeId }
            .map { c ->
                val score = scores.getValue(c)
                val reason = if (score < winnerScore) {
                    "점수 미달 (%.2f < %.2f)".format(score, winnerScore)
                } else {
                    "점수 동점 — 등록 시각/nodeId 우선순위 낮음 (score: %.2f)".format(score)
                }
                Elimination(c, reason)
            }
        return ElectionResult(winner, eliminations, scores.mapKeys { it.key.nodeId })
    }
}
