package io.bluetape4k.leader.spring.aop.autoconfigure

import com.hazelcast.core.HazelcastInstance
import com.mongodb.client.MongoClient
import io.bluetape4k.leader.LeaderElectorFactory
import io.bluetape4k.leader.LeaderGroupElectorFactory
import io.bluetape4k.leader.coroutines.LocalSuspendLeaderElectorFactory
import io.bluetape4k.leader.coroutines.LocalSuspendLeaderGroupElectorFactory
import io.bluetape4k.leader.coroutines.SuspendLeaderElectorFactory
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElectorFactory
import io.bluetape4k.leader.consul.ConsulEndpoint
import io.bluetape4k.leader.consul.ConsulLeaderElectionOptions
import io.bluetape4k.leader.consul.ConsulLeaderElectorFactory
import io.bluetape4k.leader.consul.ConsulLeaderGroupElectionOptions
import io.bluetape4k.leader.consul.ConsulLeaderGroupElectorFactory
import io.bluetape4k.leader.consul.ConsulSuspendLeaderElectorFactory
import io.bluetape4k.leader.consul.ConsulSuspendLeaderGroupElectorFactory
import io.bluetape4k.leader.dynamodb.DynamoDbLeaderElectorFactory
import io.bluetape4k.leader.dynamodb.DynamoDbLeaderGroupElectorFactory
import io.bluetape4k.leader.dynamodb.DynamoDbSuspendLeaderElectorFactory
import io.bluetape4k.leader.dynamodb.DynamoDbSuspendLeaderGroupElectorFactory
import io.bluetape4k.leader.etcd.EtcdLeaderElectionOptions
import io.bluetape4k.leader.etcd.EtcdLeaderElectorFactory
import io.bluetape4k.leader.etcd.EtcdLeaderGroupElectionOptions
import io.bluetape4k.leader.etcd.EtcdLeaderGroupElectorFactory
import io.bluetape4k.leader.etcd.EtcdSuspendLeaderElectorFactory
import io.bluetape4k.leader.etcd.EtcdSuspendLeaderGroupElectorFactory
import io.bluetape4k.leader.exposed.jdbc.ExposedJdbcLeaderElectorFactory
import io.bluetape4k.leader.exposed.jdbc.ExposedJdbcLeaderGroupElectorFactory
import io.bluetape4k.leader.exposed.r2dbc.ExposedR2DbcSuspendLeaderElectorFactory
import io.bluetape4k.leader.exposed.r2dbc.ExposedR2DbcSuspendLeaderGroupElectorFactory
import io.bluetape4k.leader.hazelcast.HazelcastLeaderElectorFactory
import io.bluetape4k.leader.hazelcast.HazelcastLeaderGroupElectorFactory
import io.bluetape4k.leader.lettuce.LettuceLeaderElectorFactory
import io.bluetape4k.leader.lettuce.LettuceLeaderGroupElectorFactory
import io.bluetape4k.leader.lettuce.LettuceSuspendLeaderElectorFactory
import io.bluetape4k.leader.lettuce.LettuceSuspendLeaderGroupElectorFactory
import io.bluetape4k.leader.local.LocalLeaderElectorFactory
import io.bluetape4k.leader.local.LocalLeaderGroupElectorFactory
import io.bluetape4k.leader.mongodb.MongoLeaderElectorFactory
import io.bluetape4k.leader.mongodb.MongoLeaderGroupElectorFactory
import io.bluetape4k.leader.mongodb.MongoSuspendLeaderElectorFactory
import io.bluetape4k.leader.mongodb.MongoSuspendLeaderGroupElectorFactory
import io.bluetape4k.leader.redisson.RedissonLeaderElectorFactory
import io.bluetape4k.leader.redisson.RedissonLeaderGroupElectorFactory
import io.bluetape4k.leader.redisson.RedissonSuspendLeaderElectorFactory
import io.bluetape4k.leader.redisson.RedissonSuspendLeaderGroupElectorFactory
import io.bluetape4k.leader.spring.LeaderProperties
import io.bluetape4k.leader.spring.adapter.PropertiesAdapter
import io.bluetape4k.leader.spring.backend.DynamoDbLeaderConfiguration
import io.etcd.jetcd.Client
import io.lettuce.core.api.StatefulRedisConnection
import org.aspectj.lang.annotation.Aspect
import org.bson.Document
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.redisson.api.RedissonClient
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.config.BeanDefinition
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Role
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import kotlin.time.toKotlinDuration

/**
 * AutoConfig Phase 1 — registers backend factory `@Bean`s (sync + suspend).
 *
 * ## [Codex H3] Separation Rationale
 * Factory `@Bean` registration is separated from the Aspect/BPP registration in [LeaderAopAutoConfiguration]
 * to avoid a `@ConditionalOnBean(LeaderElectionFactory)` self-reference.
 *
 * ## Evaluation
 * - `@ConditionalOnClass(Aspect)` — activated only when aspectjweaver is on the classpath
 * - `@ConditionalOnProperty(enabled, matchIfMissing=true)` — can be disabled via `bluetape4k.leader.aop.enabled=false`
 * - Each factory `@Bean` is guarded by `@ConditionalOnClass/Bean(BackendClient)` and registered only when the backend is in use
 *
 * ## Local fallback
 * The local factory is always registered (single-JVM fallback for all environments). Used when no other backend is specified.
 */
@AutoConfiguration
@ConditionalOnClass(Aspect::class)
@ConditionalOnProperty(prefix = "bluetape4k.leader.aop", name = ["enabled"], havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(LeaderProperties::class)
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

    @Bean(name = ["localSuspendLeaderElectorFactory"])
    @ConditionalOnMissingBean(name = ["localSuspendLeaderElectorFactory"])
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    fun localSuspendLeaderElectorFactory(): SuspendLeaderElectorFactory = LocalSuspendLeaderElectorFactory()

    @Bean(name = ["localSuspendLeaderGroupElectorFactory"])
    @ConditionalOnMissingBean(name = ["localSuspendLeaderGroupElectorFactory"])
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    fun localSuspendLeaderGroupElectorFactory(): SuspendLeaderGroupElectorFactory = LocalSuspendLeaderGroupElectorFactory()

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

        @Bean(name = ["lettuceSuspendLeaderElectorFactory"])
        @ConditionalOnBean(StatefulRedisConnection::class)
        @ConditionalOnMissingBean(name = ["lettuceSuspendLeaderElectorFactory"])
        @ConditionalOnClass(name = ["io.bluetape4k.leader.lettuce.LettuceSuspendLeaderElectorFactory"])
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        fun lettuceSuspendLeaderElectorFactory(
            connection: StatefulRedisConnection<String, String>,
        ): SuspendLeaderElectorFactory = LettuceSuspendLeaderElectorFactory(connection)

        @Bean(name = ["lettuceSuspendLeaderGroupElectorFactory"])
        @ConditionalOnBean(StatefulRedisConnection::class)
        @ConditionalOnMissingBean(name = ["lettuceSuspendLeaderGroupElectorFactory"])
        @ConditionalOnClass(name = ["io.bluetape4k.leader.lettuce.LettuceSuspendLeaderGroupElectorFactory"])
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        fun lettuceSuspendLeaderGroupElectorFactory(
            connection: StatefulRedisConnection<String, String>,
        ): SuspendLeaderGroupElectorFactory = LettuceSuspendLeaderGroupElectorFactory(connection)
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

        @Bean(name = ["redissonSuspendLeaderElectorFactory"])
        @ConditionalOnBean(RedissonClient::class)
        @ConditionalOnMissingBean(name = ["redissonSuspendLeaderElectorFactory"])
        @ConditionalOnClass(name = ["io.bluetape4k.leader.redisson.RedissonSuspendLeaderElectorFactory"])
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        fun redissonSuspendLeaderElectorFactory(client: RedissonClient): SuspendLeaderElectorFactory =
            RedissonSuspendLeaderElectorFactory(client)

        @Bean(name = ["redissonSuspendLeaderGroupElectorFactory"])
        @ConditionalOnBean(RedissonClient::class)
        @ConditionalOnMissingBean(name = ["redissonSuspendLeaderGroupElectorFactory"])
        @ConditionalOnClass(name = ["io.bluetape4k.leader.redisson.RedissonSuspendLeaderGroupElectorFactory"])
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        fun redissonSuspendLeaderGroupElectorFactory(client: RedissonClient): SuspendLeaderGroupElectorFactory =
            RedissonSuspendLeaderGroupElectorFactory(client)
    }

    // ── etcd ────────────────────────────────────────────────────

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(Client::class, EtcdLeaderElectorFactory::class)
    class EtcdFactoryConfig {

        @Bean(name = ["etcdLeaderElectionFactory"])
        @ConditionalOnBean(Client::class)
        @ConditionalOnMissingBean(name = ["etcdLeaderElectionFactory"])
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        fun etcdLeaderElectionFactory(
            client: Client,
            props: LeaderProperties,
        ): LeaderElectorFactory =
            EtcdLeaderElectorFactory(
                client,
                EtcdLeaderElectionOptions(keyPrefix = props.etcd.keyPrefix),
            )

        @Bean(name = ["etcdLeaderGroupElectionFactory"])
        @ConditionalOnBean(Client::class)
        @ConditionalOnMissingBean(name = ["etcdLeaderGroupElectionFactory"])
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        fun etcdLeaderGroupElectionFactory(
            client: Client,
            props: LeaderProperties,
        ): LeaderGroupElectorFactory =
            EtcdLeaderGroupElectorFactory(
                client,
                EtcdLeaderGroupElectionOptions(keyPrefix = props.etcd.keyPrefix),
            )

        @Bean(name = ["etcdSuspendLeaderElectorFactory"])
        @ConditionalOnBean(Client::class)
        @ConditionalOnMissingBean(name = ["etcdSuspendLeaderElectorFactory"])
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        fun etcdSuspendLeaderElectorFactory(
            client: Client,
            props: LeaderProperties,
        ): SuspendLeaderElectorFactory =
            EtcdSuspendLeaderElectorFactory(
                client,
                EtcdLeaderElectionOptions(keyPrefix = props.etcd.keyPrefix),
            )

        @Bean(name = ["etcdSuspendLeaderGroupElectorFactory"])
        @ConditionalOnBean(Client::class)
        @ConditionalOnMissingBean(name = ["etcdSuspendLeaderGroupElectorFactory"])
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        fun etcdSuspendLeaderGroupElectorFactory(
            client: Client,
            props: LeaderProperties,
        ): SuspendLeaderGroupElectorFactory =
            EtcdSuspendLeaderGroupElectorFactory(
                client,
                EtcdLeaderGroupElectionOptions(keyPrefix = props.etcd.keyPrefix),
            )
    }

    // ── Consul ──────────────────────────────────────────────────

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(ConsulEndpoint::class, ConsulLeaderElectorFactory::class)
    class ConsulFactoryConfig {

        @Bean(name = ["consulLeaderElectionFactory"])
        @ConditionalOnBean(ConsulEndpoint::class)
        @ConditionalOnMissingBean(name = ["consulLeaderElectionFactory"])
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        fun consulLeaderElectionFactory(
            endpoint: ConsulEndpoint,
            props: LeaderProperties,
        ): LeaderElectorFactory =
            ConsulLeaderElectorFactory(endpoint, consulElectionOptions(props))

        @Bean(name = ["consulLeaderGroupElectionFactory"])
        @ConditionalOnBean(ConsulEndpoint::class)
        @ConditionalOnMissingBean(name = ["consulLeaderGroupElectionFactory"])
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        fun consulLeaderGroupElectionFactory(
            endpoint: ConsulEndpoint,
            props: LeaderProperties,
        ): LeaderGroupElectorFactory =
            ConsulLeaderGroupElectorFactory(endpoint, consulGroupOptions(props))

        @Bean(name = ["consulSuspendLeaderElectorFactory"])
        @ConditionalOnBean(ConsulEndpoint::class)
        @ConditionalOnMissingBean(name = ["consulSuspendLeaderElectorFactory"])
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        fun consulSuspendLeaderElectorFactory(
            endpoint: ConsulEndpoint,
            props: LeaderProperties,
        ): SuspendLeaderElectorFactory =
            ConsulSuspendLeaderElectorFactory(endpoint, consulElectionOptions(props))

        @Bean(name = ["consulSuspendLeaderGroupElectorFactory"])
        @ConditionalOnBean(ConsulEndpoint::class)
        @ConditionalOnMissingBean(name = ["consulSuspendLeaderGroupElectorFactory"])
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        fun consulSuspendLeaderGroupElectorFactory(
            endpoint: ConsulEndpoint,
            props: LeaderProperties,
        ): SuspendLeaderGroupElectorFactory =
            ConsulSuspendLeaderGroupElectorFactory(endpoint, consulGroupOptions(props))

        private fun consulElectionOptions(props: LeaderProperties): ConsulLeaderElectionOptions =
            ConsulLeaderElectionOptions(
                leaderOptions = PropertiesAdapter.toCommonElection(props),
                keyPrefix = props.consul.keyPrefix,
                sessionNamePrefix = props.consul.sessionNamePrefix,
                lockDelay = props.consul.lockDelay.toKotlinDuration(),
            )

        private fun consulGroupOptions(props: LeaderProperties): ConsulLeaderGroupElectionOptions =
            ConsulLeaderGroupElectionOptions(
                leaderGroupOptions = PropertiesAdapter.toCommonGroup(props),
                keyPrefix = props.consul.keyPrefix,
                sessionNamePrefix = props.consul.sessionNamePrefix,
                lockDelay = props.consul.lockDelay.toKotlinDuration(),
            )
    }

    // ── DynamoDB ────────────────────────────────────────────────

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(DynamoDbClient::class, DynamoDbLeaderElectorFactory::class)
    class DynamoDbFactoryConfig {

        @Bean(name = ["dynamoDbLeaderElectionFactory"])
        @ConditionalOnBean(DynamoDbClient::class)
        @ConditionalOnMissingBean(name = ["dynamoDbLeaderElectionFactory"])
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        fun dynamoDbLeaderElectionFactory(
            client: DynamoDbClient,
            props: LeaderProperties,
        ): LeaderElectorFactory =
            DynamoDbLeaderElectorFactory(client, DynamoDbLeaderConfiguration.electionOptions(props))

        @Bean(name = ["dynamoDbLeaderGroupElectionFactory"])
        @ConditionalOnBean(DynamoDbClient::class)
        @ConditionalOnMissingBean(name = ["dynamoDbLeaderGroupElectionFactory"])
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        fun dynamoDbLeaderGroupElectionFactory(
            client: DynamoDbClient,
            props: LeaderProperties,
        ): LeaderGroupElectorFactory =
            DynamoDbLeaderGroupElectorFactory(client, DynamoDbLeaderConfiguration.groupOptions(props))
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(DynamoDbAsyncClient::class, DynamoDbSuspendLeaderElectorFactory::class)
    class DynamoDbSuspendFactoryConfig {

        @Bean(name = ["dynamoDbSuspendLeaderElectorFactory"])
        @ConditionalOnBean(DynamoDbAsyncClient::class)
        @ConditionalOnMissingBean(name = ["dynamoDbSuspendLeaderElectorFactory"])
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        fun dynamoDbSuspendLeaderElectorFactory(
            client: DynamoDbAsyncClient,
            props: LeaderProperties,
        ): SuspendLeaderElectorFactory =
            DynamoDbSuspendLeaderElectorFactory(client, DynamoDbLeaderConfiguration.electionOptions(props))

        @Bean(name = ["dynamoDbSuspendLeaderGroupElectorFactory"])
        @ConditionalOnBean(DynamoDbAsyncClient::class)
        @ConditionalOnMissingBean(name = ["dynamoDbSuspendLeaderGroupElectorFactory"])
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        fun dynamoDbSuspendLeaderGroupElectorFactory(
            client: DynamoDbAsyncClient,
            props: LeaderProperties,
        ): SuspendLeaderGroupElectorFactory =
            DynamoDbSuspendLeaderGroupElectorFactory(client, DynamoDbLeaderConfiguration.groupOptions(props))
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
            @Qualifier("leaderLockMongoCollection")
            collection: com.mongodb.client.MongoCollection<Document>,
        ): LeaderElectorFactory = MongoLeaderElectorFactory(collection)

        @Bean(name = ["mongoLeaderGroupElectionFactory"])
        @ConditionalOnBean(name = ["leaderGroupLockMongoCollection"])
        @ConditionalOnMissingBean(name = ["mongoLeaderGroupElectionFactory"])
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        fun mongoLeaderGroupElectionFactory(
            @Qualifier("leaderGroupLockMongoCollection")
            groupCollection: com.mongodb.client.MongoCollection<Document>,
        ): LeaderGroupElectorFactory = MongoLeaderGroupElectorFactory(groupCollection)
    }

    // ── MongoDB suspend (coroutine driver) ───────────────────────

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(
        name = [
            "com.mongodb.kotlin.client.coroutine.MongoCollection",
            "io.bluetape4k.leader.mongodb.MongoSuspendLeaderElectorFactory",
        ]
    )
    class MongoSuspendFactoryConfig {

        @Bean(name = ["mongoSuspendLeaderElectorFactory"])
        @ConditionalOnBean(name = ["leaderLockMongoCoroutineCollection"])
        @ConditionalOnMissingBean(name = ["mongoSuspendLeaderElectorFactory"])
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        fun mongoSuspendLeaderElectorFactory(
            @Qualifier("leaderLockMongoCoroutineCollection")
            collection: com.mongodb.kotlin.client.coroutine.MongoCollection<Document>,
        ): SuspendLeaderElectorFactory = MongoSuspendLeaderElectorFactory(collection)

        @Bean(name = ["mongoSuspendLeaderGroupElectorFactory"])
        @ConditionalOnBean(name = ["leaderGroupLockMongoCollection", "leaderGroupLockMongoCoroutineCollection"])
        @ConditionalOnMissingBean(name = ["mongoSuspendLeaderGroupElectorFactory"])
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        fun mongoSuspendLeaderGroupElectorFactory(
            @Qualifier("leaderGroupLockMongoCollection")
            syncGroupCollection: com.mongodb.client.MongoCollection<Document>,
            @Qualifier("leaderGroupLockMongoCoroutineCollection")
            coroutineGroupCollection: com.mongodb.kotlin.client.coroutine.MongoCollection<Document>,
        ): SuspendLeaderGroupElectorFactory = MongoSuspendLeaderGroupElectorFactory(syncGroupCollection, coroutineGroupCollection)
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

    // ── Exposed R2DBC suspend ─────────────────────────────────────

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(R2dbcDatabase::class, ExposedR2DbcSuspendLeaderElectorFactory::class)
    class ExposedR2dbcSuspendFactoryConfig {

        @Bean(name = ["exposedR2dbcSuspendLeaderElectorFactory"])
        @ConditionalOnBean(R2dbcDatabase::class)
        @ConditionalOnMissingBean(name = ["exposedR2dbcSuspendLeaderElectorFactory"])
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        fun exposedR2dbcSuspendLeaderElectorFactory(db: R2dbcDatabase): SuspendLeaderElectorFactory =
            ExposedR2DbcSuspendLeaderElectorFactory(db)

        @Bean(name = ["exposedR2dbcSuspendLeaderGroupElectorFactory"])
        @ConditionalOnBean(R2dbcDatabase::class)
        @ConditionalOnMissingBean(name = ["exposedR2dbcSuspendLeaderGroupElectorFactory"])
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        fun exposedR2dbcSuspendLeaderGroupElectorFactory(db: R2dbcDatabase): SuspendLeaderGroupElectorFactory =
            ExposedR2DbcSuspendLeaderGroupElectorFactory(db)
    }
}
