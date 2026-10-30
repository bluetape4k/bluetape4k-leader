package io.bluetape4k.leader.spring.backend

import io.bluetape4k.leader.history.SafeLeaderHistoryRecorder
import io.bluetape4k.leader.history.SuspendSafeLeaderHistoryRecorder
import io.bluetape4k.leader.lettuce.LettuceLeaderElector
import io.bluetape4k.leader.lettuce.LettuceLeaderGroupElector
import io.bluetape4k.leader.lettuce.LettuceSuspendLeaderElector
import io.bluetape4k.leader.lettuce.LettuceSuspendLeaderGroupElector
import io.bluetape4k.leader.spring.LeaderProperties
import io.bluetape4k.leader.spring.adapter.PropertiesAdapter
import io.bluetape4k.logging.KLogging
import io.lettuce.core.api.StatefulRedisConnection
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** Lettuce `StatefulRedisConnection` bean이 있을 때 Redis blocking, group, suspend elector를 등록합니다. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(StatefulRedisConnection::class)
@ConditionalOnBean(StatefulRedisConnection::class)
class LettuceLeaderConfiguration {

    companion object: KLogging()

    @Bean
    @ConditionalOnMissingBean(name = ["lettuceLeaderElector"])
    fun lettuceLeaderElector(
        connection: StatefulRedisConnection<String, String>,
        props: LeaderProperties,
        recorderProvider: ObjectProvider<SafeLeaderHistoryRecorder>,
    ): LettuceLeaderElector =
        LettuceLeaderElector(connection, PropertiesAdapter.toCommonElection(props), recorderProvider.ifAvailable)

    @Bean
    @ConditionalOnMissingBean(name = ["lettuceSuspendLeaderElector"])
    fun lettuceSuspendLeaderElector(
        connection: StatefulRedisConnection<String, String>,
        props: LeaderProperties,
        recorderProvider: ObjectProvider<SuspendSafeLeaderHistoryRecorder>,
    ): LettuceSuspendLeaderElector =
        LettuceSuspendLeaderElector(connection, PropertiesAdapter.toCommonElection(props), recorderProvider.ifAvailable)

    @Bean
    @ConditionalOnMissingBean(name = ["lettuceLeaderGroupElector"])
    fun lettuceLeaderGroupElector(
        connection: StatefulRedisConnection<String, String>,
        props: LeaderProperties,
    ): LettuceLeaderGroupElector =
        LettuceLeaderGroupElector(connection, PropertiesAdapter.toCommonGroup(props))

    @Bean
    @ConditionalOnMissingBean(name = ["lettuceSuspendLeaderGroupElector"])
    fun lettuceSuspendLeaderGroupElector(
        connection: StatefulRedisConnection<String, String>,
        props: LeaderProperties,
    ): LettuceSuspendLeaderGroupElector =
        LettuceSuspendLeaderGroupElector(connection, PropertiesAdapter.toCommonGroup(props))
}
