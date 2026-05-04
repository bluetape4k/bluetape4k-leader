package io.bluetape4k.leader.spring.aop.health

import io.bluetape4k.leader.spring.aop.spel.SpelExpressionEvaluator
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator

/**
 * Leader AOP Health Indicator — Actuator `/actuator/health` 노출.
 *
 * ## 한계
 * **MUST NOT call backend** — backend 핑 호출은 k8s liveness probe cascade 위험.
 * 본 indicator 는 로컬 카운터만 노출:
 * - SpEL cache size
 * - (향후 [#75] 통합 시) 누적 elected/skipped/failed 카운터
 *
 * ## 출력 예
 * ```json
 * {
 *   "status": "UP",
 *   "details": {
 *     "spelCacheSize": 12
 *   }
 * }
 * ```
 *
 * @param spel SpEL evaluator 참조 (cache size 노출용)
 */
class LeaderAopHealthIndicator(
    private val spel: SpelExpressionEvaluator,
) : HealthIndicator {

    override fun health(): Health = Health.up()
        .withDetail("spelCacheSize", spel.cacheSize())
        .build()
}
