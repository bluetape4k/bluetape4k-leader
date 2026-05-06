package io.bluetape4k.leader.spring.backend

import io.bluetape4k.leader.redisson.RedissonLeaderElector
import io.bluetape4k.leader.redisson.RedissonLeaderGroupElector
import io.bluetape4k.leader.redisson.RedissonSuspendLeaderElector
import io.bluetape4k.leader.redisson.RedissonSuspendLeaderGroupElector
import io.bluetape4k.leader.spring.LeaderProperties
import io.bluetape4k.leader.spring.adapter.PropertiesAdapter
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
    @ConditionalOnMissingBean(name = ["redissonLeaderElector"])
    fun redissonLeaderElector(
        client: RedissonClient,
        props: LeaderProperties,
    ): RedissonLeaderElector =
        RedissonLeaderElector(client, PropertiesAdapter.toCommonElection(props))

    @Bean
    @ConditionalOnMissingBean(name = ["redissonSuspendLeaderElector"])
    fun redissonSuspendLeaderElector(
        client: RedissonClient,
        props: LeaderProperties,
    ): RedissonSuspendLeaderElector =
        RedissonSuspendLeaderElector(client, PropertiesAdapter.toCommonElection(props))

    @Bean
    @ConditionalOnMissingBean(name = ["redissonLeaderGroupElector"])
    fun redissonLeaderGroupElector(
        client: RedissonClient,
        props: LeaderProperties,
    ): RedissonLeaderGroupElector =
        RedissonLeaderGroupElector(client, PropertiesAdapter.toCommonGroup(props))

    @Bean
    @ConditionalOnMissingBean(name = ["redissonSuspendLeaderGroupElector"])
    fun redissonSuspendLeaderGroupElector(
        client: RedissonClient,
        props: LeaderProperties,
    ): RedissonSuspendLeaderGroupElector =
        RedissonSuspendLeaderGroupElector(client, PropertiesAdapter.toCommonGroup(props))
}
