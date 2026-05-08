package io.bluetape4k.leader.spring

import io.bluetape4k.codec.Base58
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderGroupElector
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElector
import io.bluetape4k.leader.redisson.RedissonLeaderElector
import io.bluetape4k.leader.redisson.RedissonLeaderGroupElector
import io.bluetape4k.leader.redisson.RedissonSuspendLeaderElector
import io.bluetape4k.leader.redisson.RedissonSuspendLeaderGroupElector
import kotlinx.coroutines.runBlocking
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldNotBeNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.redisson.api.RedissonClient
import org.springframework.beans.factory.getBean
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LeaderElectionAutoConfigurationTest: AbstractRedissonAutoConfigurationTest() {

    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(LeaderElectionAutoConfiguration::class.java))
        .withUserConfiguration(RedissonClientTestConfig::class.java)

    @Test
    fun `Redisson 4 종 election 빈이 모두 정상 주입`() {
        runner.run { ctx ->
            ctx.getBean<LeaderElector>() shouldBeInstanceOf RedissonLeaderElector::class
            ctx.getBean<SuspendLeaderElector>() shouldBeInstanceOf RedissonSuspendLeaderElector::class
            ctx.getBean<LeaderGroupElector>() shouldBeInstanceOf RedissonLeaderGroupElector::class
            ctx.getBean<SuspendLeaderGroupElector>() shouldBeInstanceOf RedissonSuspendLeaderGroupElector::class
        }
    }

    @Test
    fun `runIfLeader sync 호출이 정상 동작`() {
        runner.run { ctx ->
            val election = ctx.getBean<LeaderElector>()
            val lockName = "auto-config-test-${Base58.randomString(8)}"
            val result = election.runIfLeader(lockName) { 42 }
            result shouldBeEqualTo 42
        }
    }

    @Test
    fun `runIfLeader suspend 호출이 정상 동작`() {
        runner.run { ctx ->
            val election = ctx.getBean<SuspendLeaderElector>()
            val lockName = "auto-config-suspend-${Base58.randomString(8)}"
            val result = runBlocking { election.runIfLeader(lockName) { 99 } }
            result shouldBeEqualTo 99
        }
    }

    @Test
    fun `LeaderGroupElection이 maxLeaders 만큼 슬롯 제공`() {
        runner
            .withPropertyValues("bluetape4k.leader.group.max-leaders=3")
            .run { ctx ->
                val group = ctx.getBean<LeaderGroupElector>()
                group.maxLeaders shouldBeEqualTo 3
            }
    }

    @Test
    fun `LeaderProperties가 yaml prefix bluetape4k_leader로 바인딩`() {
        runner
            .withPropertyValues(
                "bluetape4k.leader.wait-time=2s",
                "bluetape4k.leader.lease-time=15s",
            )
            .run { ctx ->
                val props = ctx.getBean<LeaderProperties>()
                props.shouldNotBeNull()
                props.waitTime.seconds shouldBeEqualTo 2L
                props.leaseTime.seconds shouldBeEqualTo 15L
            }
    }

    @Configuration(proxyBeanMethods = false)
    class RedissonClientTestConfig {
        @Bean(destroyMethod = "shutdown")
        fun redissonClient(): RedissonClient = newRedissonClient()
    }
}
