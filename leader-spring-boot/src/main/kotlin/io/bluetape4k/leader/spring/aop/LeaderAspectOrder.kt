package io.bluetape4k.leader.spring.aop

import org.springframework.core.Ordered

/**
 * Leader Aspect 의 advice 순서 상수.
 *
 * ## 외부 advice 와의 순서 표
 *
 * | Aspect | Order | 의도 |
 * |--------|-------|------|
 * | `LeaderElectionAspect` (본 라이브러리) | `HIGHEST_PRECEDENCE + 100` | 락 획득이 가장 바깥 |
 * | `@Cacheable` | `LOWEST_PRECEDENCE - 1` | 캐시 확인 |
 * | `@CircuitBreaker` (Resilience4j) | `LOWEST_PRECEDENCE - 4` | 장애 차단 |
 * | `@Retry` (Resilience4j) | `LOWEST_PRECEDENCE - 3` | 재시도 |
 * | `@Transactional` | `LOWEST_PRECEDENCE` | 트랜잭션 (가장 안쪽) |
 *
 * 결과 순서: 락 획득 → (Cache) → CircuitBreaker → Retry → Transaction → 본문 → tx 커밋 → 락 해제.
 * ShedLock 권장 동등.
 */
object LeaderAspectOrder {
    /**
     * Leader Aspect 의 권장 order. `HIGHEST_PRECEDENCE + 100` — 락 획득이 가장 바깥에 위치하면서도
     * 사용자가 더 바깥쪽 aspect 를 추가할 여지를 100 단계 남긴다.
     */
    const val AOP_ORDER: Int = Ordered.HIGHEST_PRECEDENCE + 100
}
