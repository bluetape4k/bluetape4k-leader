package io.bluetape4k.leader.strategy.scorers

import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.CandidateScorer
import io.bluetape4k.support.requireNotEmpty

/**
 * `WeightedScorer`는 전략 기반 leader 선출에서 후보 평가 규칙을 제공합니다.
 *
 * API 이름과 `lock`, `lease`, `leader`, `slot`, `audit` 용어는 코드 계약과 동일하게 유지합니다.
 * @property scorers 가중치와 함께 조합할 후보 점수 계산기 목록입니다.
 */
class WeightedScorer(
    val scorers: List<Pair<CandidateScorer, Double>>,
) : CandidateScorer {

    init {
        scorers.requireNotEmpty("scorers")
        require(scorers.all { (_, w) -> w > 0.0 }) {
            "All scorer weights must be positive: ${scorers.map { it.second }}"
        }
    }

    constructor(vararg scorers: Pair<CandidateScorer, Double>) : this(scorers.toList())

    override fun score(candidate: CandidateInfo, all: List<CandidateInfo>): Double =
        scorers.sumOf { (scorer, weight) -> scorer.score(candidate, all) * weight }
}
