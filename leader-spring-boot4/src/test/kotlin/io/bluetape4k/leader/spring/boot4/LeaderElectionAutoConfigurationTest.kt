package io.bluetape4k.leader.spring.boot4

import io.bluetape4k.codec.Base58
import io.bluetape4k.leader.LeaderElection
import io.bluetape4k.leader.LeaderGroupElection
import io.bluetape4k.leader.coroutines.SuspendLeaderElection
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElection
import io.bluetape4k.leader.redisson.RedissonLeaderElection
import io.bluetape4k.leader.redisson.RedissonLeaderGroupElection
import io.bluetape4k.leader.redisson.RedissonSuspendLeaderElection
import io.bluetape4k.leader.redisson.RedissonSuspendLeaderGroupElection
import kotlinx.coroutines.runBlocking
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeInstanceOf
import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.redisson.api.RedissonClient
import org.springframework.beans.factory.getBean
import org.springframework.beans.factory.getBeanNamesForType
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
            ctx.getBean<LeaderElection>() shouldBeInstanceOf RedissonLeaderElection::class
            ctx.getBean<SuspendLeaderElection>() shouldBeInstanceOf RedissonSuspendLeaderElection::class
            ctx.getBean<LeaderGroupElection>() shouldBeInstanceOf RedissonLeaderGroupElection::class
            ctx.getBean<SuspendLeaderGroupElection>() shouldBeInstanceOf RedissonSuspendLeaderGroupElection::class
        }
    }

    @Test
    fun `runIfLeader sync 호출이 정상 동작`() {
        runner.run { ctx ->
            val election = ctx.getBean<LeaderElection>()
            val lockName = "auto-config-test-${Base58.randomString(8)}"
            val result = election.runIfLeader(lockName) { 42 }
            result shouldBeEqualTo 42
        }
    }

    @Test
    fun `runIfLeader suspend 호출이 정상 동작`() {
        runner.run { ctx ->
            val election = ctx.getBean<SuspendLeaderElection>()
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
                val group = ctx.getBean<LeaderGroupElection>()
                group.maxLeaders shouldBeEqualTo 3
            }
    }

    @Test
    fun `Boot4LeaderProperties가 yaml prefix bluetape4k_leader로 바인딩`() {
        runner
            .withPropertyValues(
                "bluetape4k.leader.wait-time=2s",
                "bluetape4k.leader.lease-time=15s",
            )
            .run { ctx ->
                val props = ctx.getBean<Boot4LeaderProperties>()
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
