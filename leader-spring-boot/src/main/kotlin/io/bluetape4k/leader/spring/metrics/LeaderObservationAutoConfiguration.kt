package io.bluetape4k.leader.spring.metrics

import io.bluetape4k.leader.micrometer.LeaderMetricTagOptions
import io.bluetape4k.leader.micrometer.LeaderObservationOptions
import io.bluetape4k.leader.micrometer.MicrometerObservationLeaderAopMetricsRecorder
import io.bluetape4k.leader.micrometer.MicrometerObservationLeaderElectionListener
import io.bluetape4k.leader.spring.LeaderProperties
import io.bluetape4k.leader.spring.aop.autoconfigure.LeaderAopAutoConfiguration
import io.bluetape4k.leader.spring.aop.properties.LeaderAopProperties
import io.bluetape4k.leader.spring.properties.LeaderTracingProperties
import io.micrometer.observation.ObservationRegistry
import org.springframework.beans.factory.config.BeanDefinition
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Role

/**
 * `선언`는 Spring Boot integration의 leader election, route guard, metric, example workflow 계약을 설명합니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
 */
@AutoConfiguration(
    after = [LeaderMicrometerAutoConfiguration::class],
    before = [LeaderAopAutoConfiguration::class],
)
@ConditionalOnClass(name = [
    "io.micrometer.observation.ObservationRegistry",
    "io.bluetape4k.leader.micrometer.MicrometerObservationLeaderAopMetricsRecorder",
])
@ConditionalOnProperty(
    prefix = "bluetape4k.leader.observability",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
@EnableConfigurationProperties(LeaderProperties::class, LeaderAopProperties::class)
class LeaderObservationAutoConfiguration {

    /**
     * Spring Boot integration 계약을 설명하는 한국어 KDoc입니다.
     */
    @Bean
    @ConditionalOnBean(ObservationRegistry::class)
    @ConditionalOnMissingBean(MicrometerObservationLeaderAopMetricsRecorder::class)
    @ConditionalOnProperty(
        prefix = "bluetape4k.leader.observability.tracing",
        name = ["enabled"],
        havingValue = "true",
        matchIfMissing = true,
    )
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    fun micrometerObservationLeaderAopMetricsRecorder(
        registry: ObservationRegistry,
        properties: LeaderProperties,
        aopProperties: LeaderAopProperties,
    ): MicrometerObservationLeaderAopMetricsRecorder =
        MicrometerObservationLeaderAopMetricsRecorder(
            registry = registry,
            options = properties.observability.tracing.toOptions(aopProperties.metrics.tags.toMicrometerOptions()),
        )

    /**
     * Spring Boot integration 계약을 설명하는 한국어 KDoc입니다.
     */
    @Bean
    @ConditionalOnBean(ObservationRegistry::class)
    @ConditionalOnMissingBean(MicrometerObservationLeaderElectionListener::class)
    @ConditionalOnProperty(
        prefix = "bluetape4k.leader.observability.tracing",
        name = ["enabled"],
        havingValue = "true",
        matchIfMissing = true,
    )
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    fun micrometerObservationLeaderElectionListener(
        registry: ObservationRegistry,
        properties: LeaderProperties,
        aopProperties: LeaderAopProperties,
    ): MicrometerObservationLeaderElectionListener =
        MicrometerObservationLeaderElectionListener(
            registry = registry,
            options = properties.observability.tracing.toOptions(aopProperties.metrics.tags.toMicrometerOptions()),
        )
}

private fun LeaderTracingProperties.toOptions(tagOptions: LeaderMetricTagOptions): LeaderObservationOptions =
    LeaderObservationOptions(
        includeLockName = includeLockName,
        includeLeaderId = includeLeaderId,
        includeExceptionDetails = includeExceptionDetails,
        tagOptions = tagOptions,
    )
