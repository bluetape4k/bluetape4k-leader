package io.bluetape4k.leader.spring.metrics

import io.bluetape4k.leader.micrometer.LeaderObservationOptions
import io.bluetape4k.leader.micrometer.MicrometerObservationLeaderAopMetricsRecorder
import io.bluetape4k.leader.micrometer.MicrometerObservationLeaderElectionListener
import io.bluetape4k.leader.spring.LeaderProperties
import io.bluetape4k.leader.spring.aop.autoconfigure.LeaderAopAutoConfiguration
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
 * Micrometer Observation auto-configuration for leader election tracing.
 *
 * Automatically registers Observation-based recorder and listener beans when an
 * [ObservationRegistry] is available. The bridge is intentionally exporter-neutral:
 * applications provide Micrometer Tracing/OpenTelemetry exporters through their own
 * Spring Boot observability setup.
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
@EnableConfigurationProperties(LeaderProperties::class)
class LeaderObservationAutoConfiguration {

    /**
     * Registers the AOP Observation recorder when tracing is enabled and no custom
     * Observation recorder bean is present.
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
    ): MicrometerObservationLeaderAopMetricsRecorder =
        MicrometerObservationLeaderAopMetricsRecorder(registry, properties.observability.tracing.toOptions())

    /**
     * Registers a lightweight listener that emits lifecycle event observations.
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
    ): MicrometerObservationLeaderElectionListener =
        MicrometerObservationLeaderElectionListener(registry, properties.observability.tracing.toOptions())
}

private fun LeaderTracingProperties.toOptions(): LeaderObservationOptions =
    LeaderObservationOptions(
        includeLockName = includeLockName,
        includeLeaderId = includeLeaderId,
        includeExceptionDetails = includeExceptionDetails,
    )
