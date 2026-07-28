package io.bluetape4k.leader.spring.aop

import org.springframework.core.Ordered

/**
 * `LeaderAspectOrder`는 Spring Boot integration의 leader election, route guard, metric, example workflow 계약을 설명합니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
 * @property AOP_ORDER Spring Boot integration 계약에서 사용하는 속성입니다.
 */
object LeaderAspectOrder {
    /**
     * `AOP_ORDER` 값은 Spring Boot integration 계약에서 사용하는 설정 또는 상태 항목입니다.
     */
    const val AOP_ORDER: Int = Ordered.HIGHEST_PRECEDENCE + 100
}
