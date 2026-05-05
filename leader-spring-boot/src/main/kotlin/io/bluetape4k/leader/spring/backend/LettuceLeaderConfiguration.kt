package io.bluetape4k.leader.spring.backend

import io.bluetape4k.leader.lettuce.LettuceLeaderElector
import io.bluetape4k.leader.lettuce.LettuceLeaderGroupElector
import io.bluetape4k.leader.lettuce.LettuceSuspendLeaderElector
import io.bluetape4k.leader.lettuce.LettuceSuspendLeaderGroupElector
import io.bluetape4k.leader.spring.LeaderProperties
import io.bluetape4k.leader.spring.adapter.PropertiesAdapter
import io.lettuce.core.api.StatefulRedisConnection
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Lettuce(Redis) 백엔드 자동 구성.
 *
 * `StatefulRedisConnection<String, String>` 빈이 등록된 경우에만 활성화됩니다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(StatefulRedisConnection::class)
@ConditionalOnBean(StatefulRedisConnection::class)
class LettuceLeaderConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = ["lettuceLeaderElection"])
    fun lettuceLeaderElection(
        connection: StatefulRedisConnection<String, String>,
        props: LeaderProperties,
    ): LettuceLeaderElector =
        LettuceLeaderElector(connection, PropertiesAdapter.toCommonElection(props))

    @Bean
    @ConditionalOnMissingBean(name = ["lettuceSuspendLeaderElection"])
    fun lettuceSuspendLeaderElection(
        connection: StatefulRedisConnection<String, String>,
        props: LeaderProperties,
    ): LettuceSuspendLeaderElector =
        LettuceSuspendLeaderElector(connection, PropertiesAdapter.toCommonElection(props))

    @Bean
    @ConditionalOnMissingBean(name = ["lettuceLeaderGroupElection"])
    fun lettuceLeaderGroupElection(
        connection: StatefulRedisConnection<String, String>,
        props: LeaderProperties,
    ): LettuceLeaderGroupElector =
        LettuceLeaderGroupElector(connection, PropertiesAdapter.toCommonGroup(props))

    @Bean
    @ConditionalOnMissingBean(name = ["lettuceSuspendLeaderGroupElection"])
    fun lettuceSuspendLeaderGroupElection(
        connection: StatefulRedisConnection<String, String>,
        props: LeaderProperties,
    ): LettuceSuspendLeaderGroupElector =
        LettuceSuspendLeaderGroupElector(connection, PropertiesAdapter.toCommonGroup(props))
}
