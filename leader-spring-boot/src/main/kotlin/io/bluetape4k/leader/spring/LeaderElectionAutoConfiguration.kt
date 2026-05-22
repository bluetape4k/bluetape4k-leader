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

/**
 * Spring Boot auto-configuration entry point for bluetape4k-leader.
 *
 * Activated when the `LeaderElector` class is present on the classpath. Imports each backend's
 * sub-configuration via `@Import`. Backend activation conditions are checked with
 * `@ConditionalOnBean` / `@ConditionalOnClass` in the respective Configuration classes.
 *
 * The local fallback is declared as a separate `LocalLeaderConfiguration` with
 * `@AutoConfiguration(after=...)` so it activates after all backends have been evaluated.
 *
 * @see LeaderProperties for yaml `bluetape4k.leader.*` property binding
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
    fun leaderLeaseAutoExtenderLifecycle(props: LeaderProperties): LeaderLeaseAutoExtenderLifecycle =
        LeaderLeaseAutoExtenderLifecycle(
            watchdogThreads = props.watchdogThreads,
            watchdogAsyncExtend = props.watchdogAsyncExtend,
        )
}
