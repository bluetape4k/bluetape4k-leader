package io.bluetape4k.leader.identity

    /**
     * `LeaderInternalApi` 선언은 leader election 계약에서 사용되는 annotation입니다.
     *
     * API 이름과 `lock`, `lease`, `leader`, `slot`, `audit` 용어는 코드 계약과 동일하게 유지합니다.
     */
@RequiresOptIn(
    message = "This API is internal to the leader module SPI and is not intended for application code.",
    level = RequiresOptIn.Level.WARNING,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS, AnnotationTarget.PROPERTY)
annotation class LeaderInternalApi
