package io.bluetape4k.leader.strategy.scorers

import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.CandidateScorer

/**
 * 성공 후보의 마지막 완료 시각이 최근일수록 높은 점수를 줍니다. 성공 기록이나 완료 시각이 없으면 0이고,
 * 성공 후보들의 완료 시각이 모두 같으면 100입니다.
 */
object RecentSuccessScorer: CandidateScorer {

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
