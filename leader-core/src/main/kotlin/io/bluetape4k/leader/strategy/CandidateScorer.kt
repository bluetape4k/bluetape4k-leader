package io.bluetape4k.leader.strategy

/**
 * 전략 선출에서 개별 후보에 수치 점수를 부여하는 함수 계약입니다.
 */
fun interface CandidateScorer {

    /**
     * `score`는 후보 하나의 선출 점수를 계산합니다.
     *
     * @param candidate 현재 점수를 계산할 후보
     * @param all 같은 선출 라운드에서 비교할 전체 후보 목록
     * @return 후보 우선순위 계산에 사용하는 `Double` 점수
     */
    fun score(candidate: CandidateInfo, all: List<CandidateInfo>): Double
}
