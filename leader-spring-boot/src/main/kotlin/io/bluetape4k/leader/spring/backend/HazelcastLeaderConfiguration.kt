package io.bluetape4k.leader.spring.backend

import com.hazelcast.core.HazelcastInstance
import io.bluetape4k.leader.hazelcast.HazelcastLeaderElector
import io.bluetape4k.leader.hazelcast.HazelcastLeaderGroupElector
import io.bluetape4k.leader.hazelcast.HazelcastSuspendLeaderElector
import io.bluetape4k.leader.hazelcast.HazelcastSuspendLeaderGroupElector
import io.bluetape4k.leader.spring.LeaderProperties
import io.bluetape4k.leader.spring.adapter.PropertiesAdapter
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * `HazelcastLeaderConfiguration`는 Spring Boot integration의 leader election, route guard, metric, example workflow 계약을 설명합니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(HazelcastInstance::class)
@ConditionalOnBean(HazelcastInstance::class)
class HazelcastLeaderConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = ["hazelcastLeaderElector"])
    fun hazelcastLeaderElector(
        hazelcast: HazelcastInstance,
        props: LeaderProperties,
    ): HazelcastLeaderElector =
        HazelcastLeaderElector(hazelcast, PropertiesAdapter.toCommonElection(props))

    @Bean
    @ConditionalOnMissingBean(name = ["hazelcastSuspendLeaderElector"])
    fun hazelcastSuspendLeaderElector(
        hazelcast: HazelcastInstance,
        props: LeaderProperties,
    ): HazelcastSuspendLeaderElector =
        HazelcastSuspendLeaderElector(hazelcast, PropertiesAdapter.toCommonElection(props))

    @Bean
    @ConditionalOnMissingBean(name = ["hazelcastLeaderGroupElector"])
    fun hazelcastLeaderGroupElector(
        hazelcast: HazelcastInstance,
        props: LeaderProperties,
    ): HazelcastLeaderGroupElector =
        HazelcastLeaderGroupElector(hazelcast, PropertiesAdapter.toCommonGroup(props))

    @Bean
    @ConditionalOnMissingBean(name = ["hazelcastSuspendLeaderGroupElector"])
    fun hazelcastSuspendLeaderGroupElector(
        hazelcast: HazelcastInstance,
        props: LeaderProperties,
    ): HazelcastSuspendLeaderGroupElector =
        HazelcastSuspendLeaderGroupElector(hazelcast, PropertiesAdapter.toCommonGroup(props))
}
