package io.bluetape4k.leader.spring

import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.spring.backend.ConsulLeaderConfiguration
import io.bluetape4k.leader.spring.backend.DynamoDbLeaderConfiguration
import io.bluetape4k.leader.spring.backend.EtcdLeaderConfiguration
import io.bluetape4k.leader.spring.backend.ExposedJdbcLeaderConfiguration
import io.bluetape4k.leader.spring.backend.ExposedR2dbcLeaderConfiguration
import io.bluetape4k.leader.spring.backend.HazelcastLeaderConfiguration
import io.bluetape4k.leader.spring.backend.LettuceLeaderConfiguration
import io.bluetape4k.leader.spring.backend.MongoLeaderConfiguration
import io.bluetape4k.leader.spring.backend.RedissonLeaderConfiguration
import io.bluetape4k.leader.spring.boot.LeaderLeaseAutoExtenderLifecycle
import io.bluetape4k.logging.KLogging
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment

/**
 * `LeaderElector`가 classpath에 있을 때 backend별 elector configuration과 watchdog lifecycle을 등록합니다.
 */
@AutoConfiguration
@ConditionalOnClass(LeaderElector::class)
@EnableConfigurationProperties(LeaderProperties::class)
@Import(
    RedissonLeaderConfiguration::class,
    LettuceLeaderConfiguration::class,
    MongoLeaderConfiguration::class,
    HazelcastLeaderConfiguration::class,
    EtcdLeaderConfiguration::class,
    ConsulLeaderConfiguration::class,
    DynamoDbLeaderConfiguration::class,
    ExposedJdbcLeaderConfiguration::class,
    ExposedR2dbcLeaderConfiguration::class,
)
class LeaderElectionAutoConfiguration {

    private companion object: KLogging()

    @Bean
    @ConditionalOnMissingBean
    fun leaderLeaseAutoExtenderLifecycle(
        props: LeaderProperties,
        environment: Environment,
    ): LeaderLeaseAutoExtenderLifecycle =
        LeaderLeaseAutoExtenderLifecycle(
            watchdogThreads = props.watchdogThreads,
            watchdogAsyncExtend = props.watchdogAsyncExtend.takeIf {
                environment.containsProperty("bluetape4k.leader.watchdog-async-extend")
            },
        )

    /** Preserves the one-argument bean factory method published in 0.4.0. */
    fun leaderLeaseAutoExtenderLifecycle(props: LeaderProperties): LeaderLeaseAutoExtenderLifecycle =
        LeaderLeaseAutoExtenderLifecycle(
            watchdogThreads = props.watchdogThreads,
            watchdogAsyncExtend = props.watchdogAsyncExtend,
        )
}
