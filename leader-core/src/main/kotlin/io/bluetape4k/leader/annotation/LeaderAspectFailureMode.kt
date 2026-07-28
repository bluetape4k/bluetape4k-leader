package io.bluetape4k.leader.annotation

/**
 * `LeaderAspectFailureMode` 선언은 leader election 계약에서 사용되는 enum입니다.
 *
 * API 이름과 `lock`, `lease`, `leader`, `slot`, `audit` 용어는 코드 계약과 동일하게 유지합니다.
 */
enum class LeaderAspectFailureMode {
    /**
     * `INHERIT` 선언은 leader election 계약에서 사용되는 declaration입니다.
     */
    INHERIT,

    /**
     * `RETHROW` 선언은 leader election 계약에서 사용되는 declaration입니다.
     */
    RETHROW,

    /**
     * `SKIP` 선언은 leader election 계약에서 사용되는 declaration입니다.
     */
    SKIP,

    /**
     * `FAIL_OPEN_RUN` 선언은 leader election 계약에서 사용되는 declaration입니다.
     */
    FAIL_OPEN_RUN,
}
