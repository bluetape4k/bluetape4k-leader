package io.bluetape4k.leader.spring.aop.autoconfigure

import com.hazelcast.config.Config
import com.hazelcast.core.Hazelcast
import com.hazelcast.core.HazelcastInstance
import io.bluetape4k.leader.LeaderElectorFactory
import io.bluetape4k.leader.LeaderGroupElectorFactory
import io.bluetape4k.leader.hazelcast.HazelcastLeaderElectorFactory
import io.bluetape4k.leader.hazelcast.HazelcastLeaderGroupElectorFactory
import io.bluetape4k.leader.spring.LeaderTestApplication
import io.bluetape4k.logging.KLogging
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
 * [LeaderAopFactoryAutoConfiguration.HazelcastFactoryConfig] — Hazelcast factory 빈 등록 검증.
 *
 * embedded [HazelcastInstance] 빈 제공 시 `@ConditionalOnBean(HazelcastInstance::class)` 조건 충족 →
 * 2종 Hazelcast factory 빈이 등록된다.
 * (testcontainers 없이 embedded Hazelcast 사용 — `BackendConditionalTest` 동일 패턴)
 */
@SpringBootTest(
    classes = [LeaderTestApplication::class, HazelcastAopFactoryAutoConfigurationTest.TestConfig::class],
    webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@ImportAutoConfiguration(LeaderAopFactoryAutoConfiguration::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HazelcastAopFactoryAutoConfigurationTest {

    companion object : KLogging()

    @TestConfiguration
    open class TestConfig {
        @Bean(destroyMethod = "shutdown")
        fun hazelcastInstance(): HazelcastInstance {
            val cfg = Config().apply { networkConfig.join.multicastConfig.isEnabled = false }
            return Hazelcast.newHazelcastInstance(cfg)
        }
    }

    @Autowired
    private lateinit var ctx: ApplicationContext

    @Test
    fun `hazelcastLeaderElectionFactory 빈이 등록된다`() {
        ctx.getBean("hazelcastLeaderElectionFactory").shouldBeInstanceOf<HazelcastLeaderElectorFactory>()
    }

    @Test
    fun `hazelcastLeaderGroupElectionFactory 빈이 등록된다`() {
        ctx.getBean("hazelcastLeaderGroupElectionFactory").shouldBeInstanceOf<HazelcastLeaderGroupElectorFactory>()
    }

    @Test
    fun `hazelcastLeaderElectionFactory 는 LeaderElectorFactory 타입`() {
        ctx.getBean("hazelcastLeaderElectionFactory").shouldBeInstanceOf<LeaderElectorFactory>()
    }

    @Test
    fun `hazelcastLeaderGroupElectionFactory 는 LeaderGroupElectorFactory 타입`() {
        ctx.getBean("hazelcastLeaderGroupElectionFactory").shouldBeInstanceOf<LeaderGroupElectorFactory>()
    }
}
