package io.bluetape4k.leader.spring.metrics

import io.bluetape4k.leader.micrometer.MicrometerLeaderAopMetricsRecorder
import io.bluetape4k.logging.KLogging
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

/** Micrometer AOP recorder가 있을 때 metric health indicator를 등록합니다. */
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

    companion object: KLogging()

    /** `MeterRegistry`의 active gauge를 읽는 [LeaderMetricsHealthIndicator]를 등록합니다. */
    @Bean("leaderMetricsHealthIndicator")
    @ConditionalOnMissingBean(name = ["leaderMetricsHealthIndicator"])
    @Role(BeanDefinition.ROLE_APPLICATION)
    fun leaderMetricsHealthIndicator(registry: MeterRegistry): HealthIndicator =
        LeaderMetricsHealthIndicator(registry)
}
