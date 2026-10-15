package io.bluetape4k.leader.strategy.scorers

import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.CandidateScorer

/**
 * 후보의 누적 성공률에 100을 곱해 백분율 점수로 반환합니다.
 */
object SuccessRateScorer: CandidateScorer {

    override fun score(candidate: CandidateInfo, all: List<CandidateInfo>): Double =
        candidate.successRate * 100.0
}
