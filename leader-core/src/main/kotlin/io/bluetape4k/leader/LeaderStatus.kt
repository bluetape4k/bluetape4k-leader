package io.bluetape4k.leader

/**
 * `LeaderStatus`는 leader election의 현재 상태를 표현합니다.
 *
 * API 이름과 `lock`, `lease`, `leader`, `slot`, `audit` 용어는 코드 계약과 동일하게 유지합니다.
 */
enum class LeaderStatus {
    /**
     * `Empty` 선언은 leader election 계약에서 사용되는 declaration입니다.
     */
    Empty,

    /**
     * `Occupied` 선언은 leader election 계약에서 사용되는 declaration입니다.
     */
    Occupied,
}
