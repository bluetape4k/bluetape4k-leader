package io.bluetape4k.leader.strategy

/**
 * `CandidateScorer`는 전략 기반 leader 선출에서 후보 평가 규칙을 제공합니다.
 *
 * API 이름과 `lock`, `lease`, `leader`, `slot`, `audit` 용어는 코드 계약과 동일하게 유지합니다.
 */
fun interface CandidateScorer {

    /**
     * `score`는 후보 하나의 선출 점수를 계산합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @param candidate 전략 선출에서 평가 대상이 된 후보 노드 정보입니다.
     * @param all 같은 선출 라운드에 참여한 전체 후보 목록입니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
     */
    fun score(candidate: CandidateInfo, all: List<CandidateInfo>): Double
}
