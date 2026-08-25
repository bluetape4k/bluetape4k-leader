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
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Role

/**
 * Spring Boot integration 계약을 설명하는 한국어 KDoc입니다.
 */
@AutoConfiguration(after = [LeaderElectionObservabilityAutoConfiguration::class])
@ConditionalOnClass(
    name = [
        "org.springframework.boot.actuate.endpoint.annotation.Endpoint",
        "org.springframework.boot.actuate.endpoint.annotation.ReadOperation",
    ],
)
@ConditionalOnBean(LeaderElectionStatusRegistry::class, LeaderElectionState::class)
@ConditionalOnProperty(
    prefix = "management.endpoint.leaderElection",
    name = ["enabled"],
    havingValue = "true",
)
@EnableConfigurationProperties(LeaderProperties::class)
class LeaderElectionActuatorAutoConfiguration {

    @Bean("leaderElectionStatusEndpoint")
    @ConditionalOnMissingBean
    @Role(BeanDefinition.ROLE_APPLICATION)
    internal fun leaderElectionStatusEndpoint(
        beanFactory: ConfigurableListableBeanFactory,
        properties: LeaderProperties,
        registry: LeaderElectionStatusRegistry,
        acquisitionFailureWindow: ObjectProvider<LeaderAcquisitionFailureWindow>,
    ): LeaderElectionStatusEndpoint = selectedStatusEndpoint(
        beanFactory = beanFactory,
        properties = properties,
        registry = registry,
        acquisitionFailureWindow = acquisitionFailureWindow.getIfAvailable(),
    )

    /** `0.5.0`에서 공개된 3-인자 JVM bean factory descriptor를 보존합니다. */
    fun leaderElectionStatusEndpoint(
        beanFactory: ConfigurableListableBeanFactory,
        properties: LeaderProperties,
        registry: LeaderElectionStatusRegistry,
    ): LeaderElectionStatusEndpoint = selectedStatusEndpoint(
        beanFactory = beanFactory,
        properties = properties,
        registry = registry,
        acquisitionFailureWindow = null,
    )

    private fun selectedStatusEndpoint(
        beanFactory: ConfigurableListableBeanFactory,
        properties: LeaderProperties,
        registry: LeaderElectionStatusRegistry,
        acquisitionFailureWindow: LeaderAcquisitionFailureWindow?,
    ): LeaderElectionStatusEndpoint {
        val selected = LeaderElectionStateSelector(
            beanFactory,
            properties.observability.stateProviderBean,
        ).selected()
        return LeaderElectionStatusEndpoint.fromSelectedState(
            backendName = selected.backendName,
            stateProviderBean = selected.beanName,
            state = selected.state,
            registry = registry,
            acquisitionFailureWindow = acquisitionFailureWindow,
        )
    }

    /** Preserves the blocking-elector factory method published in 0.4.0. */
    fun leaderElectionStatusEndpoint(
        leaderElector: io.bluetape4k.leader.LeaderElector,
        registry: LeaderElectionStatusRegistry,
    ): LeaderElectionStatusEndpoint = LeaderElectionStatusEndpoint(leaderElector, registry)
}
