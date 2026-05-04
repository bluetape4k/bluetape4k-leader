package io.bluetape4k.leader.spring.boot3.backend

import io.bluetape4k.leader.redisson.RedissonLeaderElection
import io.bluetape4k.leader.redisson.RedissonLeaderGroupElection
import io.bluetape4k.leader.redisson.RedissonSuspendLeaderElection
import io.bluetape4k.leader.redisson.RedissonSuspendLeaderGroupElection
import io.bluetape4k.leader.spring.boot3.Boot3LeaderProperties
import io.bluetape4k.leader.spring.boot3.adapter.PropertiesAdapter
import org.redisson.api.RedissonClient
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Redisson(Redis) 백엔드 자동 구성.
 *
 * `RedissonClient` 빈이 등록된 경우에만 활성화됩니다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(RedissonClient::class)
@ConditionalOnBean(RedissonClient::class)
class RedissonLeaderConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = ["redissonLeaderElection"])
    fun redissonLeaderElection(
        client: RedissonClient,
        props: Boot3LeaderProperties,
    ): RedissonLeaderElection =
        RedissonLeaderElection(client, PropertiesAdapter.toCommonElection(props))

    @Bean
    @ConditionalOnMissingBean(name = ["redissonSuspendLeaderElection"])
    fun redissonSuspendLeaderElection(
        client: RedissonClient,
        props: Boot3LeaderProperties,
    ): RedissonSuspendLeaderElection =
        RedissonSuspendLeaderElection(client, PropertiesAdapter.toCommonElection(props))

    @Bean
    @ConditionalOnMissingBean(name = ["redissonLeaderGroupElection"])
    fun redissonLeaderGroupElection(
        client: RedissonClient,
        props: Boot3LeaderProperties,
    ): RedissonLeaderGroupElection =
        RedissonLeaderGroupElection(client, PropertiesAdapter.toCommonGroup(props))

    @Bean
    @ConditionalOnMissingBean(name = ["redissonSuspendLeaderGroupElection"])
    fun redissonSuspendLeaderGroupElection(
        client: RedissonClient,
        props: Boot3LeaderProperties,
    ): RedissonSuspendLeaderGroupElection =
        RedissonSuspendLeaderGroupElection(client, PropertiesAdapter.toCommonGroup(props))
}
