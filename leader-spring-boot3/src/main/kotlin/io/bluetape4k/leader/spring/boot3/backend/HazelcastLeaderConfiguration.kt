package io.bluetape4k.leader.spring.boot3.backend

import com.hazelcast.core.HazelcastInstance
import io.bluetape4k.leader.hazelcast.HazelcastLeaderElection
import io.bluetape4k.leader.hazelcast.HazelcastLeaderGroupElection
import io.bluetape4k.leader.hazelcast.HazelcastSuspendLeaderElection
import io.bluetape4k.leader.hazelcast.HazelcastSuspendLeaderGroupElection
import io.bluetape4k.leader.spring.boot3.Boot3LeaderProperties
import io.bluetape4k.leader.spring.boot3.adapter.PropertiesAdapter
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Hazelcast 백엔드 자동 구성.
 *
 * `HazelcastInstance` 빈이 등록된 경우에만 활성화됩니다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(HazelcastInstance::class)
@ConditionalOnBean(HazelcastInstance::class)
class HazelcastLeaderConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = ["hazelcastLeaderElection"])
    fun hazelcastLeaderElection(
        hazelcast: HazelcastInstance,
        props: Boot3LeaderProperties,
    ): HazelcastLeaderElection =
        HazelcastLeaderElection(hazelcast, PropertiesAdapter.toCommonElection(props))

    @Bean
    @ConditionalOnMissingBean(name = ["hazelcastSuspendLeaderElection"])
    fun hazelcastSuspendLeaderElection(
        hazelcast: HazelcastInstance,
        props: Boot3LeaderProperties,
    ): HazelcastSuspendLeaderElection =
        HazelcastSuspendLeaderElection(hazelcast, PropertiesAdapter.toCommonElection(props))

    @Bean
    @ConditionalOnMissingBean(name = ["hazelcastLeaderGroupElection"])
    fun hazelcastLeaderGroupElection(
        hazelcast: HazelcastInstance,
        props: Boot3LeaderProperties,
    ): HazelcastLeaderGroupElection =
        HazelcastLeaderGroupElection(hazelcast, PropertiesAdapter.toCommonGroup(props))

    @Bean
    @ConditionalOnMissingBean(name = ["hazelcastSuspendLeaderGroupElection"])
    fun hazelcastSuspendLeaderGroupElection(
        hazelcast: HazelcastInstance,
        props: Boot3LeaderProperties,
    ): HazelcastSuspendLeaderGroupElection =
        HazelcastSuspendLeaderGroupElection(hazelcast, PropertiesAdapter.toCommonGroup(props))
}
