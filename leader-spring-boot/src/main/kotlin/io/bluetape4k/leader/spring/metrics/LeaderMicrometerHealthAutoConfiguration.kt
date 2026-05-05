package io.bluetape4k.leader.spring.metrics

import io.bluetape4k.leader.micrometer.MicrometerLeaderAopMetricsRecorder
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.config.BeanDefinition
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Role

/**
 * Leader AOP Micrometer HealthIndicator AutoConfiguration.
 *
 * `MicrometerLeaderAopMetricsRecorder` 빈이 등록된 경우 [LeaderMetricsHealthIndicator]를
 * Spring Boot Actuator health endpoint에 자동 등록한다.
 *
 * ## AutoConfig 순서
 * ```
 * LeaderMicrometerAutoConfiguration  ← MicrometerLeaderAopMetricsRecorder 등록
 *   ↓
 * LeaderMicrometerHealthAutoConfiguration  ← 본 클래스 (LeaderMetricsHealthIndicator 등록)
 * ```
 *
 * ## 비활성화
 * ```yaml
 * bluetape4k:
 *   leader:
 *     aop:
 *       metrics:
 *         enabled: false   # HealthIndicator도 함께 비활성화
 * ```
 */
@AutoConfiguration(after = [LeaderMicrometerAutoConfiguration::class])
@ConditionalOnClass(
    name = [
        "org.springframework.boot.health.contributor.HealthIndicator",
        "io.bluetape4k.leader.micrometer.MicrometerLeaderAopMetricsRecorder",
    ],
)
@ConditionalOnBean(MicrometerLeaderAopMetricsRecorder::class)
@ConditionalOnProperty(
    prefix = "bluetape4k.leader.aop.metrics",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class LeaderMicrometerHealthAutoConfiguration {

    /**
     * `MeterRegistry` 빈이 존재하고 사용자가 직접 [LeaderMetricsHealthIndicator]를
     * 등록하지 않은 경우에만 자동 등록한다.
     */
    @Bean("leaderMetricsHealthIndicator")
    @ConditionalOnMissingBean(name = ["leaderMetricsHealthIndicator"])
    @Role(BeanDefinition.ROLE_APPLICATION)
    fun leaderMetricsHealthIndicator(registry: MeterRegistry): HealthIndicator =
        LeaderMetricsHealthIndicator(registry)
}
