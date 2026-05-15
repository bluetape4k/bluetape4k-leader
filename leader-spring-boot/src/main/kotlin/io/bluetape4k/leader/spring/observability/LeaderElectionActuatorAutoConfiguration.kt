package io.bluetape4k.leader.spring.observability

import io.bluetape4k.leader.LeaderElector
import org.springframework.beans.factory.config.BeanDefinition
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Role

/**
 * Auto-configuration for the opt-in leader election Actuator endpoint.
 *
 * The endpoint bean is disabled by default. Applications must enable it with
 * `management.endpoint.leaderElection.enabled=true`, and expose it over HTTP with
 * `management.endpoints.web.exposure.include`.
 */
@AutoConfiguration(after = [LeaderElectionObservabilityAutoConfiguration::class])
@ConditionalOnClass(
    name = [
        "org.springframework.boot.actuate.endpoint.annotation.Endpoint",
        "org.springframework.boot.actuate.endpoint.annotation.ReadOperation",
    ],
)
@ConditionalOnBean(LeaderElectionStatusRegistry::class, LeaderElector::class)
@ConditionalOnProperty(
    prefix = "management.endpoint.leaderElection",
    name = ["enabled"],
    havingValue = "true",
)
class LeaderElectionActuatorAutoConfiguration {

    @Bean("leaderElectionStatusEndpoint")
    @ConditionalOnMissingBean
    @Role(BeanDefinition.ROLE_APPLICATION)
    fun leaderElectionStatusEndpoint(
        leaderElector: LeaderElector,
        registry: LeaderElectionStatusRegistry,
    ): LeaderElectionStatusEndpoint =
        LeaderElectionStatusEndpoint(leaderElector, registry)
}
