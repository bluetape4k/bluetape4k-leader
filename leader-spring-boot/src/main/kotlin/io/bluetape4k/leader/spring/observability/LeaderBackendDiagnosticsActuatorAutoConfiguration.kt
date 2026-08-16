package io.bluetape4k.leader.spring.observability

import io.bluetape4k.leader.LeaderElectionState
import io.bluetape4k.leader.spring.LeaderProperties
import io.bluetape4k.leader.spring.internal.LeaderBackendDiagnosticsSelector
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

/** 선택된 leader backend의 정적 diagnostics Actuator endpoint를 구성합니다. */
@AutoConfiguration(after = [LeaderElectionObservabilityAutoConfiguration::class])
@ConditionalOnClass(
    name = [
        "org.springframework.boot.actuate.endpoint.annotation.Endpoint",
        "org.springframework.boot.actuate.endpoint.annotation.ReadOperation",
    ],
)
@ConditionalOnBean(LeaderElectionState::class)
@ConditionalOnProperty(
    prefix = "management.endpoint.leaderBackendDiagnostics",
    name = ["enabled"],
    havingValue = "true",
)
@EnableConfigurationProperties(LeaderProperties::class)
class LeaderBackendDiagnosticsActuatorAutoConfiguration {

    @Bean("leaderBackendDiagnosticsEndpoint")
    @ConditionalOnMissingBean(LeaderBackendDiagnosticsEndpoint::class)
    @Role(BeanDefinition.ROLE_APPLICATION)
    fun leaderBackendDiagnosticsEndpoint(
        beanFactory: ConfigurableListableBeanFactory,
        properties: LeaderProperties,
    ): LeaderBackendDiagnosticsEndpoint? =
        LeaderBackendDiagnosticsSelector(
            beanFactory,
            properties.observability.stateProviderBean,
        ).selectedOrNull()?.let(::LeaderBackendDiagnosticsEndpoint)
}
