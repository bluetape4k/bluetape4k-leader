package io.bluetape4k.leader.strategy

/**
 * 후보 목록에 선출 규칙을 적용해 winner, 제외 사유 및 점수 snapshot을 만드는 계약입니다.
 */
interface ElectionStrategy {

    /**
     * `elect`는 후보 목록에서 strategy 규칙에 따라 leader를 선택합니다.
     *
     * @param candidates 이번 선출 라운드에서 평가할 후보 목록
     * @return 선택된 후보가 없으면 `winner`가 null인 `ElectionResult`
     */
    fun elect(candidates: List<CandidateInfo>): ElectionResult
}
