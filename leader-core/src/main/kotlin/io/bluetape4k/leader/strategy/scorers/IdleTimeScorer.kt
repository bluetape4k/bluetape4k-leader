package io.bluetape4k.leader.strategy.scorers

import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.CandidateScorer

/**
 * `IdleTimeScorer`는 전략 기반 leader 선출에서 후보 평가 규칙을 제공합니다.
 *
 * 현재 후보의 유휴 시간(밀리초)을 전체 후보 중 최대 유휴 시간으로 나누고 100을 곱합니다.
 * 후보가 없거나 최대 유휴 시간이 0ms이면 `0.0`을 반환합니다. 점수는 0–100 범위로 제한하지
 * 않으므로 미래 `lastCompletionTime`은 음수 점수를, 최대 유휴 시간이 음수인 입력은 100을 넘는
 * 점수를 만들 수 있습니다.
 */
object IdleTimeScorer: CandidateScorer {

    override fun score(candidate: CandidateInfo, all: List<CandidateInfo>): Double {
        if (all.isEmpty()) return 0.0
        val maxIdleMillis = all.maxOf { it.idleDuration.inWholeMilliseconds }
        if (maxIdleMillis == 0L) return 0.0
        return candidate.idleDuration.inWholeMilliseconds.toDouble() / maxIdleMillis * 100.0
    }
}
