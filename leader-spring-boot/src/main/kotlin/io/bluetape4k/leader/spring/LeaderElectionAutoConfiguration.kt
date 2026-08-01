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
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment

/**
 * `선언`는 Spring Boot integration의 leader election, route guard, metric, example workflow 계약을 설명합니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
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
