package io.bluetape4k.leader.spring.observability

import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.spring.LeaderProperties
import org.springframework.beans.factory.config.BeanDefinition
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Role

/** Auto-configures the opt-in JVM-known-lock readiness contributor. */
@AutoConfiguration(after = [LeaderElectionObservabilityAutoConfiguration::class])
@ConditionalOnClass(name = ["org.springframework.boot.health.contributor.HealthIndicator"])
@ConditionalOnBean(LeaderElectionStatusRegistry::class, LeaderElector::class)
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
    fun leaderElectionReadiness(
        leaderElector: LeaderElector,
        registry: LeaderElectionStatusRegistry,
        properties: LeaderProperties,
    ): HealthIndicator =
        LeaderElectionReadinessHealthIndicator(
            leaderElector = leaderElector,
            registry = registry,
            leaseWarningThreshold = properties.observability.health.leaseWarningThreshold,
        )
}
