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
 * Spring Boot integration 계약을 설명하는 한국어 KDoc입니다.
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
     * `leaderMetricsHealthIndicator` 호출은 Spring Boot integration 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `annotation`, `auto-configuration`, `route guard`, `metric`, `example` 용어는 기존 계약과 동일하게 유지합니다.
     */
    @Bean("leaderMetricsHealthIndicator")
    @ConditionalOnMissingBean(name = ["leaderMetricsHealthIndicator"])
    @Role(BeanDefinition.ROLE_APPLICATION)
    fun leaderMetricsHealthIndicator(registry: MeterRegistry): HealthIndicator =
        LeaderMetricsHealthIndicator(registry)
}
