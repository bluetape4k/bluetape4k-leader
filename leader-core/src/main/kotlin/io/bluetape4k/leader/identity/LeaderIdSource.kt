package io.bluetape4k.leader.identity

/**
 * `LeaderIdSource` 선언은 leader election 계약에서 사용되는 enum입니다.
 *
 * API 이름과 `lock`, `lease`, `leader`, `slot`, `audit` 용어는 코드 계약과 동일하게 유지합니다.
 */
enum class LeaderIdSource {
    /**
     * `LITERAL` 선언은 leader election 계약에서 사용되는 declaration입니다.
     */
    LITERAL,
    /**
     * `SPEL` 선언은 leader election 계약에서 사용되는 declaration입니다.
     */
    SPEL,
    /**
     * `PROPERTY` 선언은 leader election 계약에서 사용되는 declaration입니다.
     */
    PROPERTY,
    /**
     * `AUTO` 선언은 leader election 계약에서 사용되는 declaration입니다.
     */
    AUTO,
}
