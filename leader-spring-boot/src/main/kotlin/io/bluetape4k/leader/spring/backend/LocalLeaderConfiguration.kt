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
 * Local(in-memory) 백엔드 default fallback 자동 구성.
 *
 * 다른 백엔드(Redisson/Lettuce/Mongo/Hazelcast/Exposed)가 활성화되지 않은 경우에만
 * Local 빈을 등록합니다. 외부 인프라 없이 dev/test 환경에서 즉시 동작합니다.
 *
 * `@AutoConfigureAfter(LeaderElectionAutoConfiguration::class)`로 백엔드 빈이 등록된 후 평가됩니다.
 */
@AutoConfiguration(after = [LeaderElectionAutoConfiguration::class])
@EnableConfigurationProperties(LeaderProperties::class)
class LocalLeaderConfiguration {

    @Bean
    @ConditionalOnMissingBean(LeaderElector::class)
    fun localLeaderElection(props: LeaderProperties): LeaderElector =
        LocalLeaderElector(PropertiesAdapter.toCommonElection(props))

    @Bean
    @ConditionalOnMissingBean(SuspendLeaderElector::class)
    fun localSuspendLeaderElection(props: LeaderProperties): SuspendLeaderElector =
        LocalSuspendLeaderElector(PropertiesAdapter.toCommonElection(props))

    @Bean
    @ConditionalOnMissingBean(LeaderGroupElector::class)
    fun localLeaderGroupElection(props: LeaderProperties): LeaderGroupElector =
        LocalLeaderGroupElector(PropertiesAdapter.toCommonGroup(props))

    @Bean
    @ConditionalOnMissingBean(SuspendLeaderGroupElector::class)
    fun localSuspendLeaderGroupElection(props: LeaderProperties): SuspendLeaderGroupElector =
        LocalSuspendLeaderGroupElector(PropertiesAdapter.toCommonGroup(props))
}
