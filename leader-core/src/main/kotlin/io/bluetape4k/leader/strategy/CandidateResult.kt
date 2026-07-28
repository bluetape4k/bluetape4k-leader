package io.bluetape4k.leader.strategy

/**
 * `CandidateResult` 선언은 leader election 계약에서 사용되는 enum입니다.
 *
 * API 이름과 `lock`, `lease`, `leader`, `slot`, `audit` 용어는 코드 계약과 동일하게 유지합니다.
 */
enum class CandidateResult {
    /**
     * `SUCCESS` 선언은 leader election 계약에서 사용되는 declaration입니다.
     */
    SUCCESS,

    /**
     * `FAILURE` 선언은 leader election 계약에서 사용되는 declaration입니다.
     */
    FAILURE,
}
