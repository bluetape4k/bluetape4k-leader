package io.bluetape4k.leader.spring.backend

import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderGroupElector
import io.bluetape4k.leader.coroutines.LocalSuspendLeaderElector
import io.bluetape4k.leader.coroutines.LocalSuspendLeaderGroupElector
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElector
import io.bluetape4k.leader.local.LocalLeaderElector
import io.bluetape4k.leader.local.LocalLeaderGroupElector
import io.bluetape4k.leader.spring.LeaderElectionAutoConfiguration
import io.bluetape4k.leader.spring.LeaderProperties
import io.bluetape4k.leader.spring.adapter.PropertiesAdapter
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

/**
 * Auto-configuration that provides a local (in-memory) backend as the default fallback.
 *
 * Registers local beans only when no other backend (Redisson/Lettuce/Mongo/Hazelcast/Exposed) is active.
 * Works immediately in dev/test environments without any external infrastructure.
 *
 * Evaluated after backend beans have been registered via `@AutoConfigureAfter(LeaderElectionAutoConfiguration::class)`.
 */
@AutoConfiguration(after = [LeaderElectionAutoConfiguration::class])
@EnableConfigurationProperties(LeaderProperties::class)
class LocalLeaderConfiguration {

    @Bean
    @ConditionalOnMissingBean(LeaderElector::class)
    fun localLeaderElector(props: LeaderProperties): LeaderElector =
        LocalLeaderElector(PropertiesAdapter.toCommonElection(props))

    @Bean
    @ConditionalOnMissingBean(SuspendLeaderElector::class)
    fun localSuspendLeaderElector(props: LeaderProperties): SuspendLeaderElector =
        LocalSuspendLeaderElector(PropertiesAdapter.toCommonElection(props))

    @Bean
    @ConditionalOnMissingBean(LeaderGroupElector::class)
    fun localLeaderGroupElector(props: LeaderProperties): LeaderGroupElector =
        LocalLeaderGroupElector(PropertiesAdapter.toCommonGroup(props))

    @Bean
    @ConditionalOnMissingBean(SuspendLeaderGroupElector::class)
    fun localSuspendLeaderGroupElector(props: LeaderProperties): SuspendLeaderGroupElector =
        LocalSuspendLeaderGroupElector(PropertiesAdapter.toCommonGroup(props))
}
