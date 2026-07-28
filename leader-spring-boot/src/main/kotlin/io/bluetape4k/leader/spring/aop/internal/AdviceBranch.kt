package io.bluetape4k.leader.spring.aop.internal

/**
 * `AdviceBranch`는 Spring Boot integration의 leader election, route guard, metric, example workflow 계약을 설명합니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
 */
internal enum class AdviceBranch {
    /**
     * Spring Boot integration 계약을 설명하는 한국어 KDoc입니다.
     */
    SYNC,

    /**
     * Spring Boot integration 계약을 설명하는 한국어 KDoc입니다.
     */
    COROUTINES,

    /**
     * Spring Boot integration 계약을 설명하는 한국어 KDoc입니다.
     */
    REACTIVE,
}
