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
import kotlin.time.toKotlinDuration

/** 선택된 leader backend의 opt-in connectivity health indicator를 구성합니다. */
@AutoConfiguration(after = [LeaderElectionObservabilityAutoConfiguration::class])
@ConditionalOnClass(name = ["org.springframework.boot.health.contributor.HealthIndicator"])
@ConditionalOnBean(LeaderElectionState::class)
@ConditionalOnProperty(
    prefix = "bluetape4k.leader.observability.backend-health",
    name = ["enabled"],
    havingValue = "true",
)
@EnableConfigurationProperties(LeaderProperties::class)
class LeaderBackendHealthAutoConfiguration {

    @Bean("leaderBackendHealthIndicator")
    @ConditionalOnMissingBean(name = ["leaderBackendHealthIndicator"])
    @Role(BeanDefinition.ROLE_APPLICATION)
    fun leaderBackendHealthIndicator(
        beanFactory: ConfigurableListableBeanFactory,
        properties: LeaderProperties,
    ): LeaderBackendHealthIndicator? =
        LeaderBackendDiagnosticsSelector(
            beanFactory,
            properties.observability.stateProviderBean,
        ).selectedOrNull()?.let { provider ->
            LeaderBackendHealthIndicator(
                provider = provider,
                timeout = properties.observability.backendHealth.timeout.toKotlinDuration(),
            )
        }
}
