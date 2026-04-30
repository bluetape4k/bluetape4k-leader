package io.bluetape4k.leader.strategy

/**
 * 후보 목록에서 리더를 선출하는 전략 인터페이스입니다.
 *
 * 모든 구현체는 동일한 [candidates] 입력에 대해 결정론적으로 동일한 결과를 반환해야 합니다.
 * 이를 통해 분산 환경에서 각 노드가 독립적으로 동일한 winner를 계산할 수 있습니다.
 *
 * 동점 발생 시 기본 tie-breaker: `registeredAt` 오름차순 → `nodeId` 사전순.
 */
fun interface ElectionStrategy {

    /**
     * [candidates] 중 리더를 선출합니다.
     *
     * @param candidates 선출에 참여할 후보 목록
     * @return 선출된 리더, 후보가 없으면 `null`
     */
    fun selectLeader(candidates: List<CandidateInfo>): CandidateInfo?
}
