package io.bluetape4k.leader.spring.backend

import io.bluetape4k.leader.consul.ConsulEndpoint
import io.bluetape4k.leader.consul.ConsulLeaderElectionOptions
import io.bluetape4k.leader.consul.ConsulLeaderElector
import io.bluetape4k.leader.consul.ConsulLeaderGroupElectionOptions
import io.bluetape4k.leader.consul.ConsulLeaderGroupElector
import io.bluetape4k.leader.consul.ConsulSuspendLeaderElector
import io.bluetape4k.leader.consul.ConsulSuspendLeaderGroupElector
import io.bluetape4k.leader.spring.LeaderProperties
import io.bluetape4k.leader.spring.adapter.PropertiesAdapter
import kotlin.time.toKotlinDuration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Consul backend auto-configuration.
 *
 * Activated only when a caller-owned [ConsulEndpoint] bean is registered.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(ConsulEndpoint::class, ConsulLeaderElector::class)
@ConditionalOnBean(ConsulEndpoint::class)
class ConsulLeaderConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = ["consulLeaderElector"])
    fun consulLeaderElector(
        endpoint: ConsulEndpoint,
        props: LeaderProperties,
    ): ConsulLeaderElector =
        ConsulLeaderElector(endpoint, electionOptions(props))

    @Bean
    @ConditionalOnMissingBean(name = ["consulSuspendLeaderElector"])
    fun consulSuspendLeaderElector(
        endpoint: ConsulEndpoint,
        props: LeaderProperties,
    ): ConsulSuspendLeaderElector =
        ConsulSuspendLeaderElector(endpoint, electionOptions(props))

    @Bean
    @ConditionalOnMissingBean(name = ["consulLeaderGroupElector"])
    fun consulLeaderGroupElector(
        endpoint: ConsulEndpoint,
        props: LeaderProperties,
    ): ConsulLeaderGroupElector =
        ConsulLeaderGroupElector(endpoint, groupOptions(props))

    @Bean
    @ConditionalOnMissingBean(name = ["consulSuspendLeaderGroupElector"])
    fun consulSuspendLeaderGroupElector(
        endpoint: ConsulEndpoint,
        props: LeaderProperties,
    ): ConsulSuspendLeaderGroupElector =
        ConsulSuspendLeaderGroupElector(endpoint, groupOptions(props))

    private fun electionOptions(props: LeaderProperties): ConsulLeaderElectionOptions =
        ConsulLeaderElectionOptions(
            leaderOptions = PropertiesAdapter.toCommonElection(props),
            keyPrefix = props.consul.keyPrefix,
            sessionNamePrefix = props.consul.sessionNamePrefix,
            lockDelay = props.consul.lockDelay.toKotlinDuration(),
        )

    private fun groupOptions(props: LeaderProperties): ConsulLeaderGroupElectionOptions =
        ConsulLeaderGroupElectionOptions(
            leaderGroupOptions = PropertiesAdapter.toCommonGroup(props),
            keyPrefix = props.consul.keyPrefix,
            sessionNamePrefix = props.consul.sessionNamePrefix,
            lockDelay = props.consul.lockDelay.toKotlinDuration(),
        )
}
