package io.bluetape4k.leader.spring.aop

import org.springframework.core.Ordered

/**
 * Advice order constants for the Leader Aspect.
 *
 * ## Ordering table relative to external advice
 *
 * | Aspect | Order | Intent |
 * |--------|-------|--------|
 * | `LeaderElectionAspect` (this library) | `HIGHEST_PRECEDENCE + 100` | Lock acquisition is the outermost |
 * | `@Cacheable` | `LOWEST_PRECEDENCE - 1` | Cache check |
 * | `@CircuitBreaker` (Resilience4j) | `LOWEST_PRECEDENCE - 4` | Circuit breaking |
 * | `@Retry` (Resilience4j) | `LOWEST_PRECEDENCE - 3` | Retry |
 * | `@Transactional` | `LOWEST_PRECEDENCE` | Transaction (innermost) |
 *
 * Execution order: lock acquire → (Cache) → CircuitBreaker → Retry → Transaction → body → tx commit → lock release.
 * Equivalent to ShedLock's recommended ordering.
 */
object LeaderAspectOrder {
    /**
     * Recommended order for the Leader Aspect. `HIGHEST_PRECEDENCE + 100` — places lock acquisition
     * as the outermost layer while leaving 100 slots for users to add even-outer aspects.
     */
    const val AOP_ORDER: Int = Ordered.HIGHEST_PRECEDENCE + 100
}
