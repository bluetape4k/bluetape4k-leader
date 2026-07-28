package io.bluetape4k.leader.strategy.scorers

import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.CandidateScorer

/**
 * `IdleTimeScorer`는 전략 기반 leader 선출에서 후보 평가 규칙을 제공합니다.
 *
 * API 이름과 `lock`, `lease`, `leader`, `slot`, `audit` 용어는 코드 계약과 동일하게 유지합니다.
 */
object IdleTimeScorer : CandidateScorer {

    override fun score(candidate: CandidateInfo, all: List<CandidateInfo>): Double {
        if (all.isEmpty()) return 0.0
        val maxIdleMillis = all.maxOf { it.idleDuration.inWholeMilliseconds }
        if (maxIdleMillis == 0L) return 0.0
        return candidate.idleDuration.inWholeMilliseconds.toDouble() / maxIdleMillis * 100.0
    }
}
