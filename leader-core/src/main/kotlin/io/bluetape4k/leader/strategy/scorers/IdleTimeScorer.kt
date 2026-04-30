package io.bluetape4k.leader.strategy.scorers

import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.CandidateScorer

/**
 * 마지막 작업 완료 이후 가장 오래 쉰 노드에 높은 점수를 부여하는 [CandidateScorer] 입니다.
 *
 * 실행 이력이 없는 노드는 [CandidateInfo.registeredAt] 기준 경과 시간으로 계산됩니다.
 * 부하 분산 목적으로 특정 노드의 작업 독점을 방지합니다.
 *
 * 점수는 후보 풀 내 최대 idle 시간을 기준으로 0.0 ~ 100.0 범위로 정규화됩니다.
 * [WeightedScorer] 와 다른 scorer 를 함께 사용할 때 단위 통일을 위함입니다.
 */
object IdleTimeScorer : CandidateScorer {

    override fun score(candidate: CandidateInfo, all: List<CandidateInfo>): Double {
        if (all.isEmpty()) return 0.0
        val maxIdleMillis = all.maxOf { it.idleDuration.toMillis() }
        if (maxIdleMillis == 0L) return 0.0
        return candidate.idleDuration.toMillis().toDouble() / maxIdleMillis * 100.0
    }
}
