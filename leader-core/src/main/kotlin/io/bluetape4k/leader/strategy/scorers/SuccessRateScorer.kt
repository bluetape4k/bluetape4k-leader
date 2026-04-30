package io.bluetape4k.leader.strategy.scorers

import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.CandidateScorer

/**
 * 성공률이 높은 노드에 높은 점수를 부여하는 [CandidateScorer] 입니다.
 *
 * 실행 이력이 없는 노드는 성공률 0.0으로 처리됩니다.
 * 복원력(Resilience) 목적으로 안정적인 노드를 선호합니다.
 */
object SuccessRateScorer : CandidateScorer {

    override fun score(candidate: CandidateInfo, all: List<CandidateInfo>): Double =
        candidate.successRate * 100.0
}
