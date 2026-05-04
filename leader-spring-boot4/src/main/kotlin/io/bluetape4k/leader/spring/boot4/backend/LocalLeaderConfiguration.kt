package io.bluetape4k.leader.spring.boot4.backend

import io.bluetape4k.leader.LeaderElection
import io.bluetape4k.leader.LeaderGroupElection
import io.bluetape4k.leader.coroutines.LocalSuspendLeaderElection
import io.bluetape4k.leader.coroutines.LocalSuspendLeaderGroupElection
import io.bluetape4k.leader.coroutines.SuspendLeaderElection
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElection
import io.bluetape4k.leader.local.LocalLeaderElection
import io.bluetape4k.leader.local.LocalLeaderGroupElection
import io.bluetape4k.leader.spring.boot4.Boot4LeaderProperties
import io.bluetape4k.leader.spring.boot4.LeaderElectionAutoConfiguration
import io.bluetape4k.leader.spring.boot4.adapter.PropertiesAdapter
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
@EnableConfigurationProperties(Boot4LeaderProperties::class)
class LocalLeaderConfiguration {

    @Bean
    @ConditionalOnMissingBean(LeaderElection::class)
    fun localLeaderElection(props: Boot4LeaderProperties): LeaderElection =
        LocalLeaderElection(PropertiesAdapter.toCommonElection(props))

    @Bean
    @ConditionalOnMissingBean(SuspendLeaderElection::class)
    fun localSuspendLeaderElection(props: Boot4LeaderProperties): SuspendLeaderElection =
        LocalSuspendLeaderElection(PropertiesAdapter.toCommonElection(props))

    @Bean
    @ConditionalOnMissingBean(LeaderGroupElection::class)
    fun localLeaderGroupElection(props: Boot4LeaderProperties): LeaderGroupElection =
        LocalLeaderGroupElection(PropertiesAdapter.toCommonGroup(props))

    @Bean
    @ConditionalOnMissingBean(SuspendLeaderGroupElection::class)
    fun localSuspendLeaderGroupElection(props: Boot4LeaderProperties): SuspendLeaderGroupElection =
        LocalSuspendLeaderGroupElection(PropertiesAdapter.toCommonGroup(props))
}
