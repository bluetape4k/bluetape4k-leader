package io.bluetape4k.leader.spring.aop.autoconfigure

import io.bluetape4k.leader.LeaderElectorFactory
import io.bluetape4k.leader.LeaderGroupElectorFactory
import io.bluetape4k.leader.coroutines.SuspendLeaderElectorFactory
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElectorFactory
import io.bluetape4k.leader.redisson.RedissonLeaderElectorFactory
import io.bluetape4k.leader.redisson.RedissonLeaderGroupElectorFactory
import io.bluetape4k.leader.redisson.RedissonSuspendLeaderElectorFactory
import io.bluetape4k.leader.redisson.RedissonSuspendLeaderGroupElectorFactory
import io.bluetape4k.leader.spring.AbstractRedissonAutoConfigurationTest
import io.bluetape4k.leader.spring.LeaderTestApplication
import io.bluetape4k.assertions.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.redisson.api.RedissonClient
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean

/**
 * [LeaderAopFactoryAutoConfiguration.RedissonFactoryConfig] — Redisson factory 빈 등록 검증.
 *
 * [RedissonClient] 빈 제공 시 `@ConditionalOnBean(RedissonClient::class)` 조건 충족 →
 * 4종 Redisson factory 빈이 등록된다.
 */
@SpringBootTest(
    classes = [LeaderTestApplication::class, RedissonAopFactoryAutoConfigurationTest.TestConfig::class],
    webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@ImportAutoConfiguration(LeaderAopFactoryAutoConfiguration::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedissonAopFactoryAutoConfigurationTest : AbstractRedissonAutoConfigurationTest() {

    @TestConfiguration
    open class TestConfig {
        @Bean(destroyMethod = "shutdown")
        fun redissonClient(): RedissonClient = newRedissonClient()
    }

    @Autowired
    private lateinit var ctx: ApplicationContext

    @Test
    fun `redissonLeaderElectionFactory 빈이 등록된다`() {
        ctx.getBean("redissonLeaderElectionFactory").shouldBeInstanceOf<RedissonLeaderElectorFactory>()
    }

    @Test
    fun `redissonLeaderGroupElectionFactory 빈이 등록된다`() {
        ctx.getBean("redissonLeaderGroupElectionFactory").shouldBeInstanceOf<RedissonLeaderGroupElectorFactory>()
    }

    @Test
    fun `redissonSuspendLeaderElectorFactory 빈이 등록된다`() {
        ctx.getBean("redissonSuspendLeaderElectorFactory").shouldBeInstanceOf<RedissonSuspendLeaderElectorFactory>()
    }

    @Test
    fun `redissonSuspendLeaderGroupElectorFactory 빈이 등록된다`() {
        ctx.getBean("redissonSuspendLeaderGroupElectorFactory").shouldBeInstanceOf<RedissonSuspendLeaderGroupElectorFactory>()
    }

    @Test
    fun `redissonLeaderElectionFactory 는 LeaderElectorFactory 타입`() {
        ctx.getBean("redissonLeaderElectionFactory").shouldBeInstanceOf<LeaderElectorFactory>()
    }

    @Test
    fun `redissonLeaderGroupElectionFactory 는 LeaderGroupElectorFactory 타입`() {
        ctx.getBean("redissonLeaderGroupElectionFactory").shouldBeInstanceOf<LeaderGroupElectorFactory>()
    }

    @Test
    fun `redissonSuspendLeaderElectorFactory 는 SuspendLeaderElectorFactory 타입`() {
        ctx.getBean("redissonSuspendLeaderElectorFactory").shouldBeInstanceOf<SuspendLeaderElectorFactory>()
    }

    @Test
    fun `redissonSuspendLeaderGroupElectorFactory 는 SuspendLeaderGroupElectorFactory 타입`() {
        ctx.getBean("redissonSuspendLeaderGroupElectorFactory").shouldBeInstanceOf<SuspendLeaderGroupElectorFactory>()
    }
}
