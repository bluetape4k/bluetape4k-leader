package io.bluetape4k.leader.spring.aop.autoconfigure

import io.bluetape4k.leader.LeaderElectorFactory
import io.bluetape4k.leader.LeaderGroupElectorFactory
import io.bluetape4k.leader.coroutines.SuspendLeaderElectorFactory
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElectorFactory
import io.bluetape4k.leader.lettuce.LettuceLeaderElectorFactory
import io.bluetape4k.leader.lettuce.LettuceLeaderGroupElectorFactory
import io.bluetape4k.leader.lettuce.LettuceSuspendLeaderElectorFactory
import io.bluetape4k.leader.lettuce.LettuceSuspendLeaderGroupElectorFactory
import io.bluetape4k.leader.spring.LeaderTestApplication
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.closeSafe
import io.bluetape4k.testcontainers.storage.RedisServer
import io.bluetape4k.utils.ShutdownQueue
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.codec.StringCodec
import io.bluetape4k.assertions.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean

/**
 * [LeaderAopFactoryAutoConfiguration.LettuceFactoryConfig] — Lettuce factory 빈 등록 검증.
 *
 * [RedisServer.Launcher.redis] Testcontainer + [StatefulRedisConnection] 빈을 제공하면
 * `@ConditionalOnBean(StatefulRedisConnection::class)` 조건이 충족되어 4종 factory 빈이 등록된다.
 */
@SpringBootTest(
    classes = [LeaderTestApplication::class, LettuceAopFactoryAutoConfigurationTest.TestConfig::class],
    webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@ImportAutoConfiguration(LeaderAopFactoryAutoConfiguration::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LettuceAopFactoryAutoConfigurationTest {

    companion object : KLogging() {
        val redis = RedisServer.Launcher.redis

        val redisClient: RedisClient by lazy {
            RedisClient.create(redis.url).also {
                ShutdownQueue.register { runCatching { it.shutdown() } }
            }
        }

        val redisConnection: StatefulRedisConnection<String, String> by lazy {
            redisClient.connect(StringCodec.UTF8).also {
                ShutdownQueue.register { it.closeSafe() }
            }
        }
    }

    @TestConfiguration
    open class TestConfig {
        @Bean
        fun statefulRedisConnection(): StatefulRedisConnection<String, String> = redisConnection
    }

    @Autowired
    private lateinit var ctx: ApplicationContext

    @Test
    fun `lettuceLeaderElectionFactory 빈이 등록된다`() {
        ctx.getBean("lettuceLeaderElectionFactory").shouldBeInstanceOf<LettuceLeaderElectorFactory>()
    }

    @Test
    fun `lettuceLeaderGroupElectionFactory 빈이 등록된다`() {
        ctx.getBean("lettuceLeaderGroupElectionFactory").shouldBeInstanceOf<LettuceLeaderGroupElectorFactory>()
    }

    @Test
    fun `lettuceSuspendLeaderElectorFactory 빈이 등록된다`() {
        ctx.getBean("lettuceSuspendLeaderElectorFactory").shouldBeInstanceOf<LettuceSuspendLeaderElectorFactory>()
    }

    @Test
    fun `lettuceSuspendLeaderGroupElectorFactory 빈이 등록된다`() {
        ctx.getBean("lettuceSuspendLeaderGroupElectorFactory").shouldBeInstanceOf<LettuceSuspendLeaderGroupElectorFactory>()
    }

    @Test
    fun `lettuceLeaderElectionFactory 는 LeaderElectorFactory 타입`() {
        ctx.getBean("lettuceLeaderElectionFactory").shouldBeInstanceOf<LeaderElectorFactory>()
    }

    @Test
    fun `lettuceLeaderGroupElectionFactory 는 LeaderGroupElectorFactory 타입`() {
        ctx.getBean("lettuceLeaderGroupElectionFactory").shouldBeInstanceOf<LeaderGroupElectorFactory>()
    }

    @Test
    fun `lettuceSuspendLeaderElectorFactory 는 SuspendLeaderElectorFactory 타입`() {
        ctx.getBean("lettuceSuspendLeaderElectorFactory").shouldBeInstanceOf<SuspendLeaderElectorFactory>()
    }

    @Test
    fun `lettuceSuspendLeaderGroupElectorFactory 는 SuspendLeaderGroupElectorFactory 타입`() {
        ctx.getBean("lettuceSuspendLeaderGroupElectorFactory").shouldBeInstanceOf<SuspendLeaderGroupElectorFactory>()
    }
}
