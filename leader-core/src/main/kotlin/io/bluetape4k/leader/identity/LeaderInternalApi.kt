package io.bluetape4k.leader.identity

/**
 * application 코드가 직접 의존하지 않아야 하는 leader module SPI에 opt-in 경고를 표시합니다.
 */
@RequiresOptIn(
    message = "This API is internal to the leader module SPI and is not intended for application code.",
    level = RequiresOptIn.Level.WARNING,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS, AnnotationTarget.PROPERTY)
annotation class LeaderInternalApi
