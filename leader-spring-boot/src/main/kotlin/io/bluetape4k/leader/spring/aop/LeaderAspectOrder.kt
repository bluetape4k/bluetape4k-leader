package io.bluetape4k.leader.spring.aop

import org.springframework.core.Ordered

/**
 * `@LeaderElection`와 `@LeaderGroupElection` AspectJ advice의 Spring `Ordered` 우선순위를 제공합니다.
 */
object LeaderAspectOrder {
    /**
     * 다른 일반 Spring advice보다 먼저 적용하기 위한 aspect order입니다.
     */
    const val AOP_ORDER: Int = Ordered.HIGHEST_PRECEDENCE + 100
}
