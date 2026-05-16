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
 * Automatically registers [LeaderMetricsHealthIndicator] with the Spring Boot Actuator health endpoint
 * when a `MicrometerLeaderAopMetricsRecorder` bean is registered.
 *
 * ## AutoConfig Order
 * ```
 * LeaderMicrometerAutoConfiguration  ← registers MicrometerLeaderAopMetricsRecorder
 *   ↓
 * LeaderMicrometerHealthAutoConfiguration  ← this class (registers LeaderMetricsHealthIndicator)
 * ```
 *
 * ## Disabling
 * ```yaml
 * bluetape4k:
 *   leader:
 *     aop:
 *       metrics:
 *         enabled: false   # also disables the HealthIndicator
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
     * Automatically registers [LeaderMetricsHealthIndicator] only when a `MeterRegistry` bean is present
     * and the user has not registered a [LeaderMetricsHealthIndicator] directly.
     */
    @Bean("leaderMetricsHealthIndicator")
    @ConditionalOnMissingBean(name = ["leaderMetricsHealthIndicator"])
    @Role(BeanDefinition.ROLE_APPLICATION)
    fun leaderMetricsHealthIndicator(registry: MeterRegistry): HealthIndicator =
        LeaderMetricsHealthIndicator(registry)
}
