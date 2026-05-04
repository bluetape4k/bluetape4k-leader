package io.bluetape4k.leader.spring.boot3.aop.autoconfigure

import com.hazelcast.core.HazelcastInstance
import com.mongodb.client.MongoClient
import io.bluetape4k.leader.LeaderElectionFactory
import io.bluetape4k.leader.LeaderGroupElectionFactory
import io.bluetape4k.leader.exposed.jdbc.ExposedJdbcLeaderElectionFactory
import io.bluetape4k.leader.exposed.jdbc.ExposedJdbcLeaderGroupElectionFactory
import io.bluetape4k.leader.hazelcast.HazelcastLeaderElectionFactory
import io.bluetape4k.leader.hazelcast.HazelcastLeaderGroupElectionFactory
import io.bluetape4k.leader.lettuce.LettuceLeaderElectionFactory
import io.bluetape4k.leader.lettuce.LettuceLeaderGroupElectionFactory
import io.bluetape4k.leader.local.LocalLeaderElectionFactory
import io.bluetape4k.leader.local.LocalLeaderGroupElectionFactory
import io.bluetape4k.leader.mongodb.MongoLeaderElectionFactory
import io.bluetape4k.leader.mongodb.MongoLeaderGroupElectionFactory
import io.bluetape4k.leader.redisson.RedissonLeaderElectionFactory
import io.bluetape4k.leader.redisson.RedissonLeaderGroupElectionFactory
import io.bluetape4k.leader.spring.aop.properties.LeaderAopProperties
import io.lettuce.core.api.StatefulRedisConnection
import org.aspectj.lang.annotation.Aspect
import org.bson.Document
import org.jetbrains.exposed.v1.jdbc.Database
import org.redisson.api.RedissonClient
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.beans.factory.config.BeanDefinition
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
 *
 * ## [Q-P1 (b)] backend 모듈 spring-context 의존 미추가
 * 본 autoconfig 가 backend factory `@Bean` 등록을 담당. backend 모듈은 순수 Kotlin 유지.
 */
@AutoConfiguration
@ConditionalOnClass(Aspect::class)
@ConditionalOnProperty(prefix = "bluetape4k.leader.aop", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class LeaderAopFactoryAutoConfiguration {

    // ── Local (always-on fallback) ───────────────────────────────

    @Bean(name = ["localLeaderElectionFactory"])
    @ConditionalOnMissingBean(name = ["localLeaderElectionFactory"])
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    fun localLeaderElectionFactory(): LeaderElectionFactory = LocalLeaderElectionFactory()

    @Bean(name = ["localLeaderGroupElectionFactory"])
    @ConditionalOnMissingBean(name = ["localLeaderGroupElectionFactory"])
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    fun localLeaderGroupElectionFactory(): LeaderGroupElectionFactory = LocalLeaderGroupElectionFactory()

    // ── Lettuce ──────────────────────────────────────────────────

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(StatefulRedisConnection::class, LettuceLeaderElectionFactory::class)
    class LettuceFactoryConfig {

        @Bean(name = ["lettuceLeaderElectionFactory"])
        @ConditionalOnBean(StatefulRedisConnection::class)
        @ConditionalOnMissingBean(name = ["lettuceLeaderElectionFactory"])
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        fun lettuceLeaderElectionFactory(
            connection: StatefulRedisConnection<String, String>,
        ): LeaderElectionFactory = LettuceLeaderElectionFactory(connection)

        @Bean(name = ["lettuceLeaderGroupElectionFactory"])
        @ConditionalOnBean(StatefulRedisConnection::class)
        @ConditionalOnMissingBean(name = ["lettuceLeaderGroupElectionFactory"])
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        fun lettuceLeaderGroupElectionFactory(
            connection: StatefulRedisConnection<String, String>,
        ): LeaderGroupElectionFactory = LettuceLeaderGroupElectionFactory(connection)
    }

    // ── Redisson ─────────────────────────────────────────────────

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(RedissonClient::class, RedissonLeaderElectionFactory::class)
    class RedissonFactoryConfig {

        @Bean(name = ["redissonLeaderElectionFactory"])
        @ConditionalOnBean(RedissonClient::class)
        @ConditionalOnMissingBean(name = ["redissonLeaderElectionFactory"])
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        fun redissonLeaderElectionFactory(client: RedissonClient): LeaderElectionFactory =
            RedissonLeaderElectionFactory(client)

        @Bean(name = ["redissonLeaderGroupElectionFactory"])
        @ConditionalOnBean(RedissonClient::class)
        @ConditionalOnMissingBean(name = ["redissonLeaderGroupElectionFactory"])
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        fun redissonLeaderGroupElectionFactory(client: RedissonClient): LeaderGroupElectionFactory =
            RedissonLeaderGroupElectionFactory(client)
    }

    // ── MongoDB sync ─────────────────────────────────────────────

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(MongoClient::class, MongoLeaderElectionFactory::class)
    class MongoFactoryConfig {

        // Mongo factory 는 collection 이 필요 — 사용자가 collection 빈을 직접 등록해야 활성화.
        // (Spring Data Mongo 의 MongoTemplate 자동 wiring 은 본 PR scope 외)

        @Bean(name = ["mongoLeaderElectionFactory"])
        @ConditionalOnBean(name = ["leaderLockMongoCollection"])
        @ConditionalOnMissingBean(name = ["mongoLeaderElectionFactory"])
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        fun mongoLeaderElectionFactory(
            @org.springframework.beans.factory.annotation.Qualifier("leaderLockMongoCollection")
            collection: com.mongodb.client.MongoCollection<Document>,
        ): LeaderElectionFactory = MongoLeaderElectionFactory(collection)

        @Bean(name = ["mongoLeaderGroupElectionFactory"])
        @ConditionalOnBean(name = ["leaderGroupLockMongoCollection"])
        @ConditionalOnMissingBean(name = ["mongoLeaderGroupElectionFactory"])
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        fun mongoLeaderGroupElectionFactory(
            @org.springframework.beans.factory.annotation.Qualifier("leaderGroupLockMongoCollection")
            groupCollection: com.mongodb.client.MongoCollection<Document>,
        ): LeaderGroupElectionFactory = MongoLeaderGroupElectionFactory(groupCollection)
    }

    // ── Hazelcast ────────────────────────────────────────────────

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(HazelcastInstance::class, HazelcastLeaderElectionFactory::class)
    class HazelcastFactoryConfig {

        @Bean(name = ["hazelcastLeaderElectionFactory"])
        @ConditionalOnBean(HazelcastInstance::class)
        @ConditionalOnMissingBean(name = ["hazelcastLeaderElectionFactory"])
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        fun hazelcastLeaderElectionFactory(hazelcast: HazelcastInstance): LeaderElectionFactory =
            HazelcastLeaderElectionFactory(hazelcast)

        @Bean(name = ["hazelcastLeaderGroupElectionFactory"])
        @ConditionalOnBean(HazelcastInstance::class)
        @ConditionalOnMissingBean(name = ["hazelcastLeaderGroupElectionFactory"])
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        fun hazelcastLeaderGroupElectionFactory(hazelcast: HazelcastInstance): LeaderGroupElectionFactory =
            HazelcastLeaderGroupElectionFactory(hazelcast)
    }

    // ── Exposed JDBC ─────────────────────────────────────────────

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(Database::class, ExposedJdbcLeaderElectionFactory::class)
    class ExposedJdbcFactoryConfig {

        @Bean(name = ["exposedJdbcLeaderElectionFactory"])
        @ConditionalOnBean(Database::class)
        @ConditionalOnMissingBean(name = ["exposedJdbcLeaderElectionFactory"])
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        fun exposedJdbcLeaderElectionFactory(db: Database): LeaderElectionFactory =
            ExposedJdbcLeaderElectionFactory(db)

        @Bean(name = ["exposedJdbcLeaderGroupElectionFactory"])
        @ConditionalOnBean(Database::class)
        @ConditionalOnMissingBean(name = ["exposedJdbcLeaderGroupElectionFactory"])
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        fun exposedJdbcLeaderGroupElectionFactory(db: Database): LeaderGroupElectionFactory =
            ExposedJdbcLeaderGroupElectionFactory(db)
    }
}
