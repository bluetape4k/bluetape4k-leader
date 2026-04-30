package io.bluetape4k.leader.strategy

/**
 * 후보 목록에서 리더를 선출하는 전략 인터페이스입니다.
 *
 * 모든 구현체는 동일한 [candidates] 입력에 대해 결정론적으로 동일한 결과를 반환해야 합니다.
 * 이를 통해 분산 환경에서 각 노드가 독립적으로 동일한 winner를 계산할 수 있습니다.
 *
 * 동점 발생 시 기본 tie-breaker: `registeredAt` 오름차순 → `nodeId` 사전순.
 *
 * ## 내장 전략
 * - [io.bluetape4k.leader.strategy.strategies.FifoElectionStrategy] — 가장 먼저 등록된 후보
 * - [io.bluetape4k.leader.strategy.strategies.RandomElectionStrategy] — 결정론적 랜덤 (seed 필요)
 * - [io.bluetape4k.leader.strategy.strategies.ScoredElectionStrategy] — 점수 최대 후보
 *
 * ## 커스텀 전략 예제
 *
 * ```kotlin
 * // 짝수 nodeId 만 winner 후보로 인정하는 전략
 * object EvenNodeStrategy : ElectionStrategy {
 *     override fun elect(candidates: List<CandidateInfo>): ElectionResult {
 *         val even = candidates.filter { it.nodeId.last().digitToIntOrNull()?.rem(2) == 0 }
 *         val winner = even.minByOrNull { it.registeredAt } ?: return ElectionResult.EMPTY
 *         val eliminations = candidates.filter { it.nodeId != winner.nodeId }
 *             .map { Elimination(it, "홀수 nodeId") }
 *         return ElectionResult(winner, eliminations)
 *     }
 * }
 * ```
 */
interface ElectionStrategy {

    /**
     * [candidates] 중 리더를 선출하고, 탈락 후보별 사유를 포함한 [ElectionResult]를 반환합니다.
     *
     * @param candidates 선출에 참여할 후보 목록
     * @return 선출 결과 (winner + 탈락자 목록)
     */
    fun elect(candidates: List<CandidateInfo>): ElectionResult
}
