package io.bluetape4k.leader.spring.boot4

import com.hazelcast.config.Config
import com.hazelcast.core.Hazelcast
import com.hazelcast.core.HazelcastInstance
import com.mongodb.client.MongoDatabase
import io.bluetape4k.codec.Base58
import io.bluetape4k.leader.exposed.jdbc.ExposedJdbcLeaderElection
import io.bluetape4k.leader.exposed.r2dbc.ExposedR2dbcSuspendLeaderElection
import io.bluetape4k.leader.mongodb.MongoLeaderElection
import io.bluetape4k.testcontainers.storage.MongoDBServer
import io.bluetape4k.testcontainers.storage.RedisServer
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import com.mongodb.kotlin.client.coroutine.MongoDatabase as CoroutineMongoDatabase
import io.bluetape4k.leader.LeaderElection
import io.bluetape4k.leader.LeaderGroupElection
import io.bluetape4k.leader.coroutines.SuspendLeaderElection
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElection
import io.bluetape4k.leader.lettuce.LettuceLeaderElection
import io.bluetape4k.leader.local.LocalLeaderElection
import io.bluetape4k.leader.redisson.RedissonLeaderElection
import io.bluetape4k.leader.spring.boot4.backend.LocalLeaderConfiguration
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeInstanceOf
import org.amshove.kluent.shouldHaveSize
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
            ctx.getBean("localLeaderElection") shouldBeInstanceOf LocalLeaderElection::class
            ctx.getBeanNamesForType<LeaderElection>() shouldHaveSize 1
            ctx.getBeanNamesForType<SuspendLeaderElection>() shouldHaveSize 1
            ctx.getBeanNamesForType<LeaderGroupElection>() shouldHaveSize 1
            ctx.getBeanNamesForType<SuspendLeaderGroupElection>() shouldHaveSize 1
        }
    }

    // ─────────────── Redisson ───────────────

    @Test
    fun `RedissonClient 빈 등록 시 Redisson 4 빈 활성 + Local 비활성`() {
        contextRunner
            .withUserConfiguration(RedissonClientConfig::class.java)
            .run { ctx ->
                ctx.getBean("redissonLeaderElection") shouldBeInstanceOf RedissonLeaderElection::class
                ctx.getBeanNamesForType<LeaderElection>() shouldHaveSize 1
                ctx.getBeanNamesForType<SuspendLeaderElection>() shouldHaveSize 1
                ctx.getBeanNamesForType<LeaderGroupElection>() shouldHaveSize 1
                ctx.getBeanNamesForType<SuspendLeaderGroupElection>() shouldHaveSize 1
                ctx.containsBean("localLeaderElection") shouldBeEqualTo false
            }
    }

    // ─────────────── Lettuce ───────────────

    @Test
    fun `StatefulRedisConnection 빈 등록 시 Lettuce 4 빈 활성 + Local 비활성`() {
        contextRunner
            .withUserConfiguration(LettuceConnectionConfig::class.java)
            .run { ctx ->
                ctx.getBean("lettuceLeaderElection") shouldBeInstanceOf LettuceLeaderElection::class
                ctx.containsBean("localLeaderElection") shouldBeEqualTo false
            }
    }

    // ─────────────── Hazelcast ───────────────

    @Test
    fun `HazelcastInstance 빈 등록 시 Hazelcast 4 빈 활성 + Local 비활성`() {
        contextRunner
            .withUserConfiguration(HazelcastInstanceConfig::class.java)
            .run { ctx ->
                ctx.containsBean("hazelcastLeaderElection") shouldBeEqualTo true
                ctx.containsBean("hazelcastSuspendLeaderElection") shouldBeEqualTo true
                ctx.containsBean("hazelcastLeaderGroupElection") shouldBeEqualTo true
                ctx.containsBean("hazelcastSuspendLeaderGroupElection") shouldBeEqualTo true
                ctx.containsBean("localLeaderElection") shouldBeEqualTo false
            }
    }

    // ─────────────── ExposedJdbc ───────────────

    @Test
    fun `Database 빈 등록 시 ExposedJdbc 빈 활성 + Local 비활성`() {
        contextRunner
            .withUserConfiguration(ExposedJdbcConfig::class.java)
            .run { ctx ->
                ctx.getBean("exposedJdbcLeaderElection") shouldBeInstanceOf ExposedJdbcLeaderElection::class
                ctx.containsBean("exposedJdbcLeaderGroupElection") shouldBeEqualTo true
                ctx.containsBean("exposedJdbcVirtualThreadLeaderElection") shouldBeEqualTo true
                ctx.containsBean("localLeaderElection") shouldBeEqualTo false
            }
    }

    // ─────────────── ExposedR2dbc ───────────────

    @Test
    fun `R2dbcDatabase 빈 등록 시 ExposedR2dbc suspend 빈 활성 + Local 비활성`() {
        contextRunner
            .withUserConfiguration(ExposedR2dbcConfig::class.java)
            .run { ctx ->
                ctx.getBean("exposedR2dbcSuspendLeaderElection") shouldBeInstanceOf ExposedR2dbcSuspendLeaderElection::class
                ctx.containsBean("exposedR2dbcSuspendLeaderGroupElection") shouldBeEqualTo true
                ctx.containsBean("localSuspendLeaderElection") shouldBeEqualTo false
            }
    }

    // ─────────────── Mongo ───────────────

    @Test
    fun `MongoDatabase 빈 등록 시 Mongo sync 빈 활성 + Local 비활성`() {
        contextRunner
            .withUserConfiguration(MongoSyncConfig::class.java)
            .run { ctx ->
                ctx.getBean("mongoLeaderElection") shouldBeInstanceOf MongoLeaderElection::class
                ctx.containsBean("mongoLeaderGroupElection") shouldBeEqualTo true
                ctx.containsBean("localLeaderElection") shouldBeEqualTo false
            }
    }

    @Test
    fun `MongoDatabase 와 CoroutineMongoDatabase 빈 등록 시 Mongo 4 빈 모두 활성`() {
        contextRunner
            .withUserConfiguration(MongoSyncConfig::class.java, MongoCoroutineConfig::class.java)
            .run { ctx ->
                ctx.containsBean("mongoLeaderElection") shouldBeEqualTo true
                ctx.containsBean("mongoSuspendLeaderElection") shouldBeEqualTo true
                ctx.containsBean("mongoLeaderGroupElection") shouldBeEqualTo true
                ctx.containsBean("mongoSuspendLeaderGroupElection") shouldBeEqualTo true
            }
    }

    // ─────────────── 다중 백엔드 ───────────────

    @Test
    fun `다중 백엔드 활성 시 빈 이름 다르므로 모두 등록 + Local 비활성`() {
        contextRunner
            .withUserConfiguration(RedissonClientConfig::class.java, LettuceConnectionConfig::class.java)
            .run { ctx ->
                ctx.containsBean("redissonLeaderElection") shouldBeEqualTo true
                ctx.containsBean("lettuceLeaderElection") shouldBeEqualTo true
                ctx.containsBean("localLeaderElection") shouldBeEqualTo false
                ctx.getBeanNamesForType<LeaderElection>() shouldHaveSize 2
            }
    }

    @Test
    fun `사용자 명명 빈은 자동 빈을 override`() {
        contextRunner
            .withUserConfiguration(UserOverrideRedissonConfig::class.java)
            .run { ctx ->
                val bean = ctx.getBean("redissonLeaderElection")
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
        val custom: RedissonLeaderElection = RedissonLeaderElection(newRedissonClient())

        @Bean
        fun redissonClient(): RedissonClient = newRedissonClient()

        @Bean(name = ["redissonLeaderElection"])
        fun customRedisson(): RedissonLeaderElection = custom
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

}
