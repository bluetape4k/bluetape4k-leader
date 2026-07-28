package io.bluetape4k.leader.strategy.strategies

import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.CandidateScorer
import io.bluetape4k.leader.strategy.ElectionResult
import io.bluetape4k.leader.strategy.ElectionStrategy
import io.bluetape4k.leader.strategy.Elimination

/**
 * `ScoredElectionStrategy`는 전략 기반 leader 선출에서 후보 평가 규칙을 제공합니다.
 *
 * API 이름과 `lock`, `lease`, `leader`, `slot`, `audit` 용어는 코드 계약과 동일하게 유지합니다.
 * @property scorer 후보 점수를 계산하는 전략 객체입니다.
 */
class ScoredElectionStrategy(val scorer: CandidateScorer) : ElectionStrategy {

    override fun elect(candidates: List<CandidateInfo>): ElectionResult {
        if (candidates.isEmpty()) return ElectionResult.EMPTY
        val scores = candidates.associateWith { scorer.score(it, candidates) }
        val maxScore = scores.values.maxOrNull() ?: return ElectionResult.EMPTY
        val topCandidates = candidates.filter { scores[it] == maxScore }
        if (topCandidates.isEmpty()) return ElectionResult.EMPTY
        val winner = topCandidates.minWith(
            compareBy(CandidateInfo::registeredAt).thenBy(CandidateInfo::nodeId)
        )
        val winnerScore = scores.getValue(winner)
        val eliminations = candidates
            .filter { it.nodeId != winner.nodeId }
            .map { c ->
                val score = scores.getValue(c)
                val reason = if (score < winnerScore) {
                    "score below winner (%.2f < %.2f)".format(score, winnerScore)
                } else {
                    "tied score — ranked lower by registeredAt/nodeId (score: %.2f)".format(score)
                }
                Elimination(c, reason)
            }
        return ElectionResult(winner, eliminations, scores.mapKeys { it.key.nodeId })
    }
}
