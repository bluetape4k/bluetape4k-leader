package io.bluetape4k.leader.strategy.strategies

import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.CandidateScorer
import io.bluetape4k.leader.strategy.Elimination
import io.bluetape4k.leader.strategy.GroupElectionStrategy
import io.bluetape4k.leader.strategy.StrategicGroupElectionResult
import io.bluetape4k.support.requireFinite
import io.bluetape4k.support.requireGe

/**
 * `ScoredGroupElectionStrategy`는 후보 점수와 결정론적 tie-break로 최대 N개를 선택합니다.
 *
 * 점수는 `CandidateScorer`로 후보마다 한 번 계산하며, 점수 내림차순,
 * `registeredAt` 오름차순, `nodeId` 사전순으로 결과를 정렬합니다.
 * @property scorer 후보 점수를 계산하는 전략 객체입니다.
 */
class ScoredGroupElectionStrategy(
    val scorer: CandidateScorer,
) : GroupElectionStrategy {

    override fun elect(
        candidates: List<CandidateInfo>,
        maxLeaders: Int,
    ): StrategicGroupElectionResult {
        maxLeaders.requireGe(1, "maxLeaders")
        if (candidates.isEmpty()) return StrategicGroupElectionResult.EMPTY

        val scores = candidates.associateWith {
            scorer.score(it, candidates).requireFinite("score") {
                "Candidate score must be finite: nodeId=${it.nodeId}"
            }
        }
        val ordered = candidates.sortedWith(
            compareByDescending<CandidateInfo> { scores.getValue(it) }
                .thenBy(CandidateInfo::registeredAt)
                .thenBy(CandidateInfo::nodeId)
        )
        val winners = ordered.take(maxLeaders)
        val boundary = winners.last()
        val boundaryScore = scores.getValue(boundary)
        val eliminations = ordered.drop(maxLeaders).map { candidate ->
            val score = scores.getValue(candidate)
            val reason = if (score < boundaryScore) {
                "score below boundary (%.2f < %.2f)".format(score, boundaryScore)
            } else {
                "tied score — ranked lower by registeredAt/nodeId (score: %.2f)".format(score)
            }
            Elimination(candidate, reason)
        }
        return StrategicGroupElectionResult(
            winners = winners,
            eliminations = eliminations,
            scores = scores.mapKeys { it.key.nodeId },
        )
    }
}
