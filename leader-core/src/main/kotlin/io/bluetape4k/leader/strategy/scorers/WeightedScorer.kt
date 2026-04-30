package io.bluetape4k.leader.strategy.scorers

import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.CandidateScorer

/**
 * 복수의 [CandidateScorer] 에 가중치를 적용해 합산하는 복합 scorer 입니다.
 *
 * ```kotlin
 * val scorer = WeightedScorer(
 *     IdleTimeScorer to 0.6,
 *     SuccessRateScorer to 0.4,
 * )
 * ```
 *
 * @property scorers (scorer, weight) 쌍 목록. weight 합계가 1.0일 필요는 없음.
 */
class WeightedScorer(
    val scorers: List<Pair<CandidateScorer, Double>>,
) : CandidateScorer {

    init {
        require(scorers.isNotEmpty()) { "WeightedScorer requires at least one scorer" }
        require(scorers.all { (_, w) -> w > 0.0 }) {
            "All scorer weights must be positive: ${scorers.map { it.second }}"
        }
    }

    constructor(vararg scorers: Pair<CandidateScorer, Double>) : this(scorers.toList())

    override fun score(candidate: CandidateInfo, all: List<CandidateInfo>): Double =
        scorers.sumOf { (scorer, weight) -> scorer.score(candidate, all) * weight }
}
