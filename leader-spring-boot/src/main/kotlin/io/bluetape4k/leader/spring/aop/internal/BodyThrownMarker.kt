package io.bluetape4k.leader.spring.aop.internal

/**
 * `BodyThrownMarker`는 Spring Boot integration의 leader election, route guard, metric, example workflow 계약을 설명합니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
 * @property cause Spring Boot integration 계약에서 사용하는 속성입니다.
 */
internal class BodyThrownMarker(override val cause: Throwable) : RuntimeException(cause)
