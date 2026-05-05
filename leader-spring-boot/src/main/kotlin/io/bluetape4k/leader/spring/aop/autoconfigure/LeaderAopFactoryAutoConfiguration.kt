package io.bluetape4k.leader.spring.aop.autoconfigure

import com.hazelcast.core.HazelcastInstance
import com.mongodb.client.MongoClient
import io.bluetape4k.leader.LeaderElectorFactory
import io.bluetape4k.leader.LeaderGroupElectorFactory
import io.bluetape4k.leader.exposed.jdbc.ExposedJdbcLeaderElectorFactory
import io.bluetape4k.leader.exposed.jdbc.ExposedJdbcLeaderGroupElectorFactory
import io.bluetape4k.leader.hazelcast.HazelcastLeaderElectorFactory
import io.bluetape4k.leader.hazelcast.HazelcastLeaderGroupElectorFactory
import io.bluetape4k.leader.lettuce.LettuceLeaderElectorFactory
import io.bluetape4k.leader.lettuce.LettuceLeaderGroupElectorFactory
import io.bluetape4k.leader.local.LocalLeaderElectorFactory
import io.bluetape4k.leader.local.LocalLeaderGroupElectorFactory
import io.bluetape4k.leader.mongodb.MongoLeaderElectorFactory
import io.bluetape4k.leader.mongodb.MongoLeaderGroupElectorFactory
import io.bluetape4k.leader.redisson.RedissonLeaderElectorFactory
import io.bluetape4k.leader.redisson.RedissonLeaderGroupElectorFactory
import io.lettuce.core.api.StatefulRedisConnection
import org.aspectj.lang.annotation.Aspect
import org.bson.Document
import org.jetbrains.exposed.v1.jdbc.Database
import org.redisson.api.RedissonClient
import org.springframework.beans.factory.config.BeanDefinition
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Role

/**
 * AutoConfig Phase 1 — 6 backend factory `@Bean` 등록.
 *
 * ## [Codex H3] 분리 사유
 * factory `@Bean` 등록을 [LeaderAopAutoConfiguration] 의 Aspect/BPP 등록과 분리하여
 * `@ConditionalOnBean(LeaderElectionFactory)` self-reference 회피.
 *
 * ## 평가
 * - `@ConditionalOnClass(Aspect)` — aspectjweaver classpath 시에만 활성화
 * - `@ConditionalOnProperty(enabled, matchIfMissing=true)` — `bluetape4k.leader.aop.enabled=false` 로 disable 가능
 * - 각 factory `@Bean` 은 `@ConditionalOnClass/Bean(BackendClient)` 가드로 backend 사용 시에만 등록
 *
 * ## Local fallback
 * Local factory 는 무조건 등록 (모든 환경에서 단일 JVM fallback). 사용자가 다른 backend 를 명시 안 하면 Local 사용.
 */
@AutoConfiguration
@ConditionalOnClass(Aspect::class)
@ConditionalOnProperty(prefix = "bluetape4k.leader.aop", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class LeaderAopFactoryAutoConfiguration {

    // ── Local (always-on fallback) ───────────────────────────────

    @Bean(name = ["localLeaderElectionFactory"])
    @ConditionalOnMissingBean(name = ["localLeaderElectionFactory"])
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    fun localLeaderElectionFactory(): LeaderElectorFactory = LocalLeaderElectorFactory()

    @Bean(name = ["localLeaderGroupElectionFactory"])
    @ConditionalOnMissingBean(name = ["localLeaderGroupElectionFactory"])
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    fun localLeaderGroupElectionFactory(): LeaderGroupElectorFactory = LocalLeaderGroupElectorFactory()

    // ── Lettuce ──────────────────────────────────────────────────

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(StatefulRedisConnection::class, LettuceLeaderElectorFactory::class)
    class LettuceFactoryConfig {

        @Bean(name = ["lettuceLeaderElectionFactory"])
        @ConditionalOnBean(StatefulRedisConnection::class)
        @ConditionalOnMissingBean(name = ["lettuceLeaderElectionFactory"])
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        fun lettuceLeaderElectionFactory(
            connection: StatefulRedisConnection<String, String>,
        ): LeaderElectorFactory = LettuceLeaderElectorFactory(connection)

        @Bean(name = ["lettuceLeaderGroupElectionFactory"])
        @ConditionalOnBean(StatefulRedisConnection::class)
        @ConditionalOnMissingBean(name = ["lettuceLeaderGroupElectionFactory"])
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        fun lettuceLeaderGroupElectionFactory(
            connection: StatefulRedisConnection<String, String>,
        ): LeaderGroupElectorFactory = LettuceLeaderGroupElectorFactory(connection)
    }

    // ── Redisson ─────────────────────────────────────────────────

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(RedissonClient::class, RedissonLeaderElectorFactory::class)
    class RedissonFactoryConfig {

        @Bean(name = ["redissonLeaderElectionFactory"])
        @ConditionalOnBean(RedissonClient::class)
        @ConditionalOnMissingBean(name = ["redissonLeaderElectionFactory"])
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        fun redissonLeaderElectionFactory(client: RedissonClient): LeaderElectorFactory =
            RedissonLeaderElectorFactory(client)

        @Bean(name = ["redissonLeaderGroupElectionFactory"])
        @ConditionalOnBean(RedissonClient::class)
        @ConditionalOnMissingBean(name = ["redissonLeaderGroupElectionFactory"])
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        fun redissonLeaderGroupElectionFactory(client: RedissonClient): LeaderGroupElectorFactory =
            RedissonLeaderGroupElectorFactory(client)
    }

    // ── MongoDB sync ─────────────────────────────────────────────

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(MongoClient::class, MongoLeaderElectorFactory::class)
    class MongoFactoryConfig {

        @Bean(name = ["mongoLeaderElectionFactory"])
        @ConditionalOnBean(name = ["leaderLockMongoCollection"])
        @ConditionalOnMissingBean(name = ["mongoLeaderElectionFactory"])
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        fun mongoLeaderElectionFactory(
            @org.springframework.beans.factory.annotation.Qualifier("leaderLockMongoCollection")
            collection: com.mongodb.client.MongoCollection<Document>,
        ): LeaderElectorFactory = MongoLeaderElectorFactory(collection)

        @Bean(name = ["mongoLeaderGroupElectionFactory"])
        @ConditionalOnBean(name = ["leaderGroupLockMongoCollection"])
        @ConditionalOnMissingBean(name = ["mongoLeaderGroupElectionFactory"])
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        fun mongoLeaderGroupElectionFactory(
            @org.springframework.beans.factory.annotation.Qualifier("leaderGroupLockMongoCollection")
            groupCollection: com.mongodb.client.MongoCollection<Document>,
        ): LeaderGroupElectorFactory = MongoLeaderGroupElectorFactory(groupCollection)
    }

    // ── Hazelcast ────────────────────────────────────────────────

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(HazelcastInstance::class, HazelcastLeaderElectorFactory::class)
    class HazelcastFactoryConfig {

        @Bean(name = ["hazelcastLeaderElectionFactory"])
        @ConditionalOnBean(HazelcastInstance::class)
        @ConditionalOnMissingBean(name = ["hazelcastLeaderElectionFactory"])
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        fun hazelcastLeaderElectionFactory(hazelcast: HazelcastInstance): LeaderElectorFactory =
            HazelcastLeaderElectorFactory(hazelcast)

        @Bean(name = ["hazelcastLeaderGroupElectionFactory"])
        @ConditionalOnBean(HazelcastInstance::class)
        @ConditionalOnMissingBean(name = ["hazelcastLeaderGroupElectionFactory"])
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        fun hazelcastLeaderGroupElectionFactory(hazelcast: HazelcastInstance): LeaderGroupElectorFactory =
            HazelcastLeaderGroupElectorFactory(hazelcast)
    }

    // ── Exposed JDBC ─────────────────────────────────────────────

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(Database::class, ExposedJdbcLeaderElectorFactory::class)
    class ExposedJdbcFactoryConfig {

        @Bean(name = ["exposedJdbcLeaderElectionFactory"])
        @ConditionalOnBean(Database::class)
        @ConditionalOnMissingBean(name = ["exposedJdbcLeaderElectionFactory"])
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        fun exposedJdbcLeaderElectionFactory(db: Database): LeaderElectorFactory =
            ExposedJdbcLeaderElectorFactory(db)

        @Bean(name = ["exposedJdbcLeaderGroupElectionFactory"])
        @ConditionalOnBean(Database::class)
        @ConditionalOnMissingBean(name = ["exposedJdbcLeaderGroupElectionFactory"])
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        fun exposedJdbcLeaderGroupElectionFactory(db: Database): LeaderGroupElectorFactory =
            ExposedJdbcLeaderGroupElectorFactory(db)
    }
}
