package io.bluetape4k.leader.spring

import com.hazelcast.config.Config
import com.hazelcast.core.Hazelcast
import com.hazelcast.core.HazelcastInstance
import com.mongodb.client.MongoDatabase
import io.bluetape4k.codec.Base58
import io.bluetape4k.leader.etcd.EtcdLeaderElector
import io.bluetape4k.leader.exposed.jdbc.ExposedJdbcLeaderElector
import io.bluetape4k.leader.exposed.r2dbc.ExposedR2DbcSuspendLeaderElector
import io.bluetape4k.leader.mongodb.MongoLeaderElector
import io.bluetape4k.testcontainers.storage.MongoDBServer
import io.bluetape4k.testcontainers.storage.RedisServer
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import com.mongodb.kotlin.client.coroutine.MongoDatabase as CoroutineMongoDatabase
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderGroupElector
import io.bluetape4k.leader.consul.ConsulEndpoint
import io.bluetape4k.leader.consul.ConsulLeaderElector
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElector
import io.bluetape4k.leader.dynamodb.DynamoDbLeaderElector
import io.bluetape4k.leader.dynamodb.DynamoDbVirtualThreadLeaderElector
import io.bluetape4k.leader.lettuce.LettuceLeaderElector
import io.bluetape4k.leader.local.LocalLeaderElector
import io.bluetape4k.leader.redisson.RedissonLeaderElector
import io.bluetape4k.leader.spring.backend.LocalLeaderConfiguration
import io.etcd.jetcd.Client
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldHaveSize
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.redisson.Redisson
import org.redisson.api.RedissonClient
import org.redisson.config.Config as RedissonConfig
import org.springframework.beans.factory.getBean
import org.springframework.beans.factory.getBeanNamesForType
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.dynamodb.DynamoDbClient

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BackendConditionalTest {

    companion object {
        private val redis: RedisServer = RedisServer.Launcher.redis
        private val redisUrl: String get() = redis.url

        private val mongo: MongoDBServer = MongoDBServer.Launcher.mongoDB
        private val sharedDbName = "test_leader_${Base58.randomString(8)}"

        private fun newRedissonClient(): RedissonClient = Redisson.create(
            RedissonConfig().apply {
                useSingleServer().setAddress(redisUrl).setConnectionPoolSize(2).setConnectionMinimumIdleSize(1)
            },
        )
    }

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                LeaderElectionAutoConfiguration::class.java,
                LocalLeaderConfiguration::class.java,
            ),
        )

    // ─────────────── Local fallback ───────────────

    @Test
    fun `백엔드 빈 미설정 시 Local 4 빈만 활성`() {
        contextRunner.run { ctx ->
            ctx.getBean("localLeaderElector") shouldBeInstanceOf LocalLeaderElector::class
            ctx.getBeanNamesForType<LeaderElector>() shouldHaveSize 1
            ctx.getBeanNamesForType<SuspendLeaderElector>() shouldHaveSize 1
            ctx.getBeanNamesForType<LeaderGroupElector>() shouldHaveSize 1
            ctx.getBeanNamesForType<SuspendLeaderGroupElector>() shouldHaveSize 1
        }
    }

    // ─────────────── Redisson ───────────────

    @Test
    fun `RedissonClient 빈 등록 시 Redisson 4 빈 활성 + Local 비활성`() {
        contextRunner
            .withUserConfiguration(RedissonClientConfig::class.java)
            .run { ctx ->
                ctx.getBean("redissonLeaderElector") shouldBeInstanceOf RedissonLeaderElector::class
                ctx.getBeanNamesForType<LeaderElector>() shouldHaveSize 1
                ctx.getBeanNamesForType<SuspendLeaderElector>() shouldHaveSize 1
                ctx.getBeanNamesForType<LeaderGroupElector>() shouldHaveSize 1
                ctx.getBeanNamesForType<SuspendLeaderGroupElector>() shouldHaveSize 1
                ctx.containsBean("localLeaderElector") shouldBeEqualTo false
            }
    }

    // ─────────────── Lettuce ───────────────

    @Test
    fun `StatefulRedisConnection 빈 등록 시 Lettuce 4 빈 활성 + Local 비활성`() {
        contextRunner
            .withUserConfiguration(LettuceConnectionConfig::class.java)
            .run { ctx ->
                ctx.getBean("lettuceLeaderElector") shouldBeInstanceOf LettuceLeaderElector::class
                ctx.containsBean("localLeaderElector") shouldBeEqualTo false
            }
    }

    // ─────────────── Hazelcast ───────────────

    @Test
    fun `HazelcastInstance 빈 등록 시 Hazelcast 4 빈 활성 + Local 비활성`() {
        contextRunner
            .withUserConfiguration(HazelcastInstanceConfig::class.java)
            .run { ctx ->
                ctx.containsBean("hazelcastLeaderElector") shouldBeEqualTo true
                ctx.containsBean("hazelcastSuspendLeaderElector") shouldBeEqualTo true
                ctx.containsBean("hazelcastLeaderGroupElector") shouldBeEqualTo true
                ctx.containsBean("hazelcastSuspendLeaderGroupElector") shouldBeEqualTo true
                ctx.containsBean("localLeaderElector") shouldBeEqualTo false
            }
    }

    // ─────────────── ExposedJdbc ───────────────

    @Test
    fun `Database 빈 등록 시 ExposedJdbc 빈 활성 + Local 비활성`() {
        contextRunner
            .withUserConfiguration(ExposedJdbcConfig::class.java)
            .run { ctx ->
                ctx.getBean("exposedJdbcLeaderElector") shouldBeInstanceOf ExposedJdbcLeaderElector::class
                ctx.containsBean("exposedJdbcLeaderGroupElector") shouldBeEqualTo true
                ctx.containsBean("exposedJdbcVirtualThreadLeaderElector") shouldBeEqualTo true
                ctx.containsBean("localLeaderElector") shouldBeEqualTo false
            }
    }

    // ─────────────── ExposedR2dbc ───────────────

    @Test
    fun `R2dbcDatabase 빈 등록 시 ExposedR2dbc suspend 빈 활성 + Local 비활성`() {
        contextRunner
            .withUserConfiguration(ExposedR2dbcConfig::class.java)
            .run { ctx ->
                ctx.getBean("exposedR2dbcSuspendLeaderElector") shouldBeInstanceOf ExposedR2DbcSuspendLeaderElector::class
                ctx.containsBean("exposedR2dbcSuspendLeaderGroupElector") shouldBeEqualTo true
                ctx.containsBean("localSuspendLeaderElector") shouldBeEqualTo false
            }
    }

    // ─────────────── Mongo ───────────────

    @Test
    fun `MongoDatabase 빈 등록 시 Mongo sync 빈 활성 + Local 비활성`() {
        contextRunner
            .withUserConfiguration(MongoSyncConfig::class.java)
            .run { ctx ->
                ctx.getBean("mongoLeaderElector") shouldBeInstanceOf MongoLeaderElector::class
                ctx.containsBean("mongoLeaderGroupElector") shouldBeEqualTo true
                ctx.containsBean("localLeaderElector") shouldBeEqualTo false
            }
    }

    @Test
    fun `MongoDatabase 와 CoroutineMongoDatabase 빈 등록 시 Mongo 4 빈 모두 활성`() {
        contextRunner
            .withUserConfiguration(MongoSyncConfig::class.java, MongoCoroutineConfig::class.java)
            .run { ctx ->
                ctx.containsBean("mongoLeaderElector") shouldBeEqualTo true
                ctx.containsBean("mongoSuspendLeaderElector") shouldBeEqualTo true
                ctx.containsBean("mongoLeaderGroupElector") shouldBeEqualTo true
                ctx.containsBean("mongoSuspendLeaderGroupElector") shouldBeEqualTo true
            }
    }

    // ─────────────── etcd ───────────────

    @Test
    fun `jetcd Client 빈 등록 시 etcd 4 빈 활성 + Local 비활성`() {
        contextRunner
            .withUserConfiguration(EtcdClientConfig::class.java)
            .withPropertyValues("bluetape4k.leader.etcd.key-prefix=/apps/orders/leader")
            .run { ctx ->
                ctx.getBean("etcdLeaderElector") shouldBeInstanceOf EtcdLeaderElector::class
                ctx.containsBean("etcdSuspendLeaderElector") shouldBeEqualTo true
                ctx.containsBean("etcdLeaderGroupElector") shouldBeEqualTo true
                ctx.containsBean("etcdSuspendLeaderGroupElector") shouldBeEqualTo true
                ctx.containsBean("localLeaderElector") shouldBeEqualTo false
            }
    }

    // ─────────────── Consul ───────────────

    @Test
    fun `ConsulEndpoint 빈 등록 시 Consul 4 빈 활성 + Local 비활성`() {
        contextRunner
            .withUserConfiguration(ConsulEndpointConfig::class.java)
            .withPropertyValues(
                "bluetape4k.leader.lease-time=10s",
                "bluetape4k.leader.group.lease-time=10s",
                "bluetape4k.leader.consul.key-prefix=apps/orders/leader",
                "bluetape4k.leader.consul.session-name-prefix=orders-leader",
            )
            .run { ctx ->
                ctx.getBean("consulLeaderElector") shouldBeInstanceOf ConsulLeaderElector::class
                ctx.containsBean("consulSuspendLeaderElector") shouldBeEqualTo true
                ctx.containsBean("consulLeaderGroupElector") shouldBeEqualTo true
                ctx.containsBean("consulSuspendLeaderGroupElector") shouldBeEqualTo true
                ctx.containsBean("localLeaderElector") shouldBeEqualTo false
            }
    }

    // ─────────────── DynamoDB ───────────────

    @Test
    fun `DynamoDbClient 빈 등록 시 DynamoDB 6 빈 활성 + Local 비활성`() {
        contextRunner
            .withUserConfiguration(DynamoDbClientConfig::class.java)
            .withPropertyValues(
                "bluetape4k.leader.dynamodb.table-name=leader_test",
                "bluetape4k.leader.dynamodb.key-prefix=apps-orders",
                "bluetape4k.leader.dynamodb.clock-skew-tolerance=100ms",
            )
            .run { ctx ->
                ctx.getBean("dynamoDbLeaderElector") shouldBeInstanceOf DynamoDbLeaderElector::class
                ctx.containsBean("dynamoDbLeaderGroupElector") shouldBeEqualTo true
                ctx.getBean("dynamoDbVirtualThreadLeaderElector") shouldBeInstanceOf
                    DynamoDbVirtualThreadLeaderElector::class
                ctx.containsBean("dynamoDbVirtualThreadLeaderGroupElector") shouldBeEqualTo true
                ctx.containsBean("dynamoDbSuspendLeaderElector") shouldBeEqualTo true
                ctx.containsBean("dynamoDbSuspendLeaderGroupElector") shouldBeEqualTo true
                ctx.containsBean("localLeaderElector") shouldBeEqualTo false
            }
    }

    // ─────────────── 다중 백엔드 ───────────────

    @Test
    fun `다중 백엔드 활성 시 빈 이름 다르므로 모두 등록 + Local 비활성`() {
        contextRunner
            .withUserConfiguration(RedissonClientConfig::class.java, LettuceConnectionConfig::class.java)
            .run { ctx ->
                ctx.containsBean("redissonLeaderElector") shouldBeEqualTo true
                ctx.containsBean("lettuceLeaderElector") shouldBeEqualTo true
                ctx.containsBean("localLeaderElector") shouldBeEqualTo false
                ctx.getBeanNamesForType<LeaderElector>() shouldHaveSize 2
            }
    }

    @Test
    fun `사용자 명명 빈은 자동 빈을 override`() {
        contextRunner
            .withUserConfiguration(UserOverrideRedissonConfig::class.java)
            .run { ctx ->
                val bean = ctx.getBean("redissonLeaderElector")
                (bean === ctx.getBean<UserOverrideRedissonConfig>().custom) shouldBeEqualTo true
            }
    }

    // ─────────────── helpers ───────────────

    @Configuration(proxyBeanMethods = false)
    class RedissonClientConfig {
        @Bean(destroyMethod = "shutdown")
        fun redissonClient(): RedissonClient = newRedissonClient()
    }

    @Configuration(proxyBeanMethods = false)
    class LettuceConnectionConfig {
        @Bean(destroyMethod = "close")
        fun lettuceConnection(): StatefulRedisConnection<String, String> =
            RedisClient.create(redisUrl).connect()
    }

    @Configuration(proxyBeanMethods = false)
    class HazelcastInstanceConfig {
        @Bean(destroyMethod = "shutdown")
        fun hazelcastInstance(): HazelcastInstance {
            val cfg = Config().apply { networkConfig.join.multicastConfig.isEnabled = false }
            return Hazelcast.newHazelcastInstance(cfg)
        }
    }

    @Configuration(proxyBeanMethods = false)
    class UserOverrideRedissonConfig {
        val custom: RedissonLeaderElector = RedissonLeaderElector(newRedissonClient())

        @Bean
        fun redissonClient(): RedissonClient = newRedissonClient()

        @Bean(name = ["redissonLeaderElector"])
        fun customRedisson(): RedissonLeaderElector = custom
    }

    @Configuration(proxyBeanMethods = false)
    class ExposedJdbcConfig {
        @Bean
        fun exposedDatabase(): Database =
            Database.connect("jdbc:h2:mem:autoconfig-jdbc-${Base58.randomString(8)};DB_CLOSE_DELAY=-1")
    }

    @Configuration(proxyBeanMethods = false)
    class ExposedR2dbcConfig {
        @Bean
        fun r2dbcDatabase(): R2dbcDatabase =
            R2dbcDatabase.connect("r2dbc:h2:mem:///autoconfig-r2dbc-${Base58.randomString(8)};MODE=MySQL;DB_CLOSE_DELAY=-1")
    }

    @Configuration(proxyBeanMethods = false)
    class MongoSyncConfig {
        @Bean
        fun mongoDatabase(): MongoDatabase = MongoDBServer.Launcher.getClient().getDatabase(sharedDbName)
    }

    @Configuration(proxyBeanMethods = false)
    class MongoCoroutineConfig {
        @Bean
        fun coroutineMongoDatabase(): CoroutineMongoDatabase =
            MongoDBServer.Launcher.getCoroutineClient().getDatabase(sharedDbName)
    }

    @Configuration(proxyBeanMethods = false)
    class EtcdClientConfig {
        @Bean
        fun etcdClient(): Client = mockk(relaxed = true)
    }

    @Configuration(proxyBeanMethods = false)
    class ConsulEndpointConfig {
        @Bean
        fun consulEndpoint(): ConsulEndpoint = ConsulEndpoint("http://localhost:8500")
    }

    @Configuration(proxyBeanMethods = false)
    class DynamoDbClientConfig {
        @Bean
        fun dynamoDbClient(): DynamoDbClient = mockk(relaxed = true)

        @Bean
        fun dynamoDbAsyncClient(): DynamoDbAsyncClient = mockk(relaxed = true)
    }
}
