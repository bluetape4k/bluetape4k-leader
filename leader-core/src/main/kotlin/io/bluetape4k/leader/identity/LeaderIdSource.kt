package io.bluetape4k.leader.identity

/**
 * leader ID 표현식이 선택되는 방식을 나타냅니다.
 */
enum class LeaderIdSource {
    /**
     * 설정한 문자열을 leader ID로 그대로 사용합니다.
     */
    LITERAL,

    /**
     * Spring Expression Language 표현식을 평가해 leader ID를 정합니다.
     */
    SPEL,

    /**
     * 환경 또는 application property에서 leader ID를 가져옵니다.
     */
    PROPERTY,

    /**
     * 설정값의 형태를 보고 literal, SpEL 또는 property 방식으로 자동 판별합니다.
     */
    AUTO,
}
