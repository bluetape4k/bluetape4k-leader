package io.bluetape4k.leader.strategy

/**
 * 전략 선출에서 후보 평가 결과를 구분합니다.
 *
 */
enum class CandidateResult {
    /**
     * 후보 실행 또는 평가가 성공했습니다.
     */
    SUCCESS,

    /**
     * 후보 실행 또는 평가가 실패했습니다.
     */
    FAILURE,
}
