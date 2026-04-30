package io.bluetape4k.leader.strategy.scorers

import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.CandidateScorer

/**
 * 가장 최근에 성공 완료한 노드에 높은 점수를 부여하는 [CandidateScorer] 입니다.
 *
 * 점수 계산:
 * - 마지막 실행이 성공: 후보 풀 내 최신 완료 시각 기준으로 0.0 ~ 100.0 정규화
 * - 마지막 실행이 실패 또는 이력 없음: 0.0
 *
 * 직전 작업이 성공한 노드를 재선출하여 연속 성공 가능성을 높입니다.
 *
 * ```kotlin
 * // sticky leader: 성공한 노드 우선 + 일부 부하 분산
 * val scorer = WeightedScorer(
 *     RecentSuccessScorer to 0.7,
 *     IdleTimeScorer to 0.3,
 * )
 * val strategy = ScoredElectionStrategy(scorer)
 * ```
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
