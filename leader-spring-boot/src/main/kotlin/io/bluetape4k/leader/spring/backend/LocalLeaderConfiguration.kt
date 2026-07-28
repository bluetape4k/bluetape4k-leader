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
 * `LocalLeaderConfiguration`는 Spring Boot integration의 leader election, route guard, metric, example workflow 계약을 설명합니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
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
