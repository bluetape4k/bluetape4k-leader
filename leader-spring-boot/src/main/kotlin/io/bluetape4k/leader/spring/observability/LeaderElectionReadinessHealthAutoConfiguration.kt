package io.bluetape4k.leader.spring.observability

import io.bluetape4k.leader.LeaderElectionState
import io.bluetape4k.leader.spring.internal.LeaderElectionStateSelector
import io.bluetape4k.leader.spring.LeaderProperties
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.config.BeanDefinition
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Role

/**
 * Spring Boot integration 계약을 설명하는 한국어 KDoc입니다.
 */
@AutoConfiguration(after = [LeaderElectionObservabilityAutoConfiguration::class])
@ConditionalOnClass(name = ["org.springframework.boot.health.contributor.HealthIndicator"])
@ConditionalOnBean(LeaderElectionStatusRegistry::class, LeaderElectionState::class)
@ConditionalOnProperty(
    prefix = "bluetape4k.leader.observability.health",
    name = ["enabled"],
    havingValue = "true",
)
@EnableConfigurationProperties(LeaderProperties::class)
class LeaderElectionReadinessHealthAutoConfiguration {

    @Bean("leaderElectionReadiness")
    @ConditionalOnMissingBean(name = ["leaderElectionReadiness"])
    @Role(BeanDefinition.ROLE_APPLICATION)
    internal fun leaderElectionReadiness(
        beanFactory: ConfigurableListableBeanFactory,
        registry: LeaderElectionStatusRegistry,
        properties: LeaderProperties,
        acquisitionFailureWindow: ObjectProvider<LeaderAcquisitionFailureWindow>,
    ): HealthIndicator = selectedReadiness(
        beanFactory = beanFactory,
        registry = registry,
        properties = properties,
        acquisitionFailureWindow = acquisitionFailureWindow.getIfAvailable(),
    )

    /** `0.5.0`에서 공개된 3-인자 JVM bean factory descriptor를 보존합니다. */
    fun leaderElectionReadiness(
        beanFactory: ConfigurableListableBeanFactory,
        registry: LeaderElectionStatusRegistry,
        properties: LeaderProperties,
    ): HealthIndicator = selectedReadiness(
        beanFactory = beanFactory,
        registry = registry,
        properties = properties,
        acquisitionFailureWindow = null,
    )

    private fun selectedReadiness(
        beanFactory: ConfigurableListableBeanFactory,
        registry: LeaderElectionStatusRegistry,
        properties: LeaderProperties,
        acquisitionFailureWindow: LeaderAcquisitionFailureWindow?,
    ): HealthIndicator {
        val selected = LeaderElectionStateSelector(
            beanFactory,
            properties.observability.stateProviderBean,
        ).selected()
        return LeaderElectionReadinessHealthIndicator.fromSelectedState(
            backendName = selected.backendName,
            stateProviderBean = selected.beanName,
            state = selected.state,
            registry = registry,
            leaseWarningThreshold = properties.observability.health.leaseWarningThreshold,
            acquisitionFailureWindow = acquisitionFailureWindow,
        )
    }
}
