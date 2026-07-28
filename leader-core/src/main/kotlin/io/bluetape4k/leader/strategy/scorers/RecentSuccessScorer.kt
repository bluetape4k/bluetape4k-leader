package io.bluetape4k.leader.strategy.scorers

import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.CandidateScorer

/**
 * `RecentSuccessScorer`는 전략 기반 leader 선출에서 후보 평가 규칙을 제공합니다.
 *
 * API 이름과 `lock`, `lease`, `leader`, `slot`, `audit` 용어는 코드 계약과 동일하게 유지합니다.
 */
object RecentSuccessScorer : CandidateScorer {

    override fun score(candidate: CandidateInfo, all: List<CandidateInfo>): Double {
        if (candidate.successCount == 0L) return 0.0
        val lastCompletion = candidate.lastCompletionTime ?: return 0.0
        val lastStart = candidate.lastStartTime
        if (lastStart != null && lastCompletion.isBefore(lastStart)) return 0.0

        val successfulCompletions = all.mapNotNull { c ->
            if (c.successCount > 0) c.lastCompletionTime?.toEpochMilli() else null
        }
        if (successfulCompletions.isEmpty()) return 0.0
        val minEpoch = successfulCompletions.min()
        val maxEpoch = successfulCompletions.max()
        if (maxEpoch == minEpoch) return 100.0
        return (lastCompletion.toEpochMilli() - minEpoch).toDouble() / (maxEpoch - minEpoch) * 100.0
    }
}
