package io.bluetape4k.leader.spring.boot3.metrics

import io.bluetape4k.leader.micrometer.MicrometerLeaderAopMetricsRecorder
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean

/**
 * Spring Boot 3 Micrometer leader metrics HealthContributor AutoConfiguration.
 *
 * [LeaderMicrometerAutoConfiguration]이 등록한 [MicrometerLeaderAopMetricsRecorder] 빈이 있을 때만 활성화된다.
 * 별도 AutoConfig 클래스로 분리하여 동일 클래스 내 `@ConditionalOnBean` 순서 문제를 회피한다.
 *
 * 사용자가 커스텀 [io.bluetape4k.leader.spring.aop.metrics.LeaderAopMetricsRecorder]를 등록한 경우
 * [MicrometerLeaderAopMetricsRecorder]가 등록되지 않으므로 이 AutoConfig도 비활성화된다.
 */
@AutoConfiguration(after = [LeaderMicrometerAutoConfiguration::class])
@ConditionalOnClass(name = [
    "io.micrometer.core.instrument.MeterRegistry",
    "io.bluetape4k.leader.micrometer.MicrometerLeaderAopMetricsRecorder",
    "org.springframework.boot.actuate.health.HealthIndicator",
])
@ConditionalOnBean(MicrometerLeaderAopMetricsRecorder::class)
@ConditionalOnProperty(
    prefix = "bluetape4k.leader.aop.metrics",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class LeaderMicrometerHealthAutoConfiguration {

    @Bean(name = ["leaderMicrometerHealthContributor"])
    @ConditionalOnMissingBean(name = ["leaderMicrometerHealthContributor"])
    fun leaderMicrometerHealthContributor(registry: MeterRegistry): HealthIndicator = HealthIndicator {
        val counters = registry.find("leader.aop.attempts").counters()
        val attemptsTotal = counters.sumOf { it.count() }
        Health.up()
            .withDetail("metrics.registered", counters.isNotEmpty())
            .withDetail("attempts.total", attemptsTotal)
            .build()
    }
}
