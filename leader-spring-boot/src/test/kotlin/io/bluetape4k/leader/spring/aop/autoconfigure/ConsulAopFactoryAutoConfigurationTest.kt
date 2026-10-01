package io.bluetape4k.leader.spring.aop.autoconfigure

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderElectorFactory
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LeaderGroupElectorFactory
import io.bluetape4k.leader.consul.ConsulEndpoint
import io.bluetape4k.leader.consul.ConsulLeaderElector
import io.bluetape4k.leader.consul.ConsulLeaderElectorFactory
import io.bluetape4k.leader.consul.ConsulLeaderGroupElector
import io.bluetape4k.leader.consul.ConsulLeaderGroupElectorFactory
import io.bluetape4k.leader.consul.ConsulSuspendLeaderElector
import io.bluetape4k.leader.consul.ConsulSuspendLeaderElectorFactory
import io.bluetape4k.leader.consul.ConsulSuspendLeaderGroupElector
import io.bluetape4k.leader.consul.ConsulSuspendLeaderGroupElectorFactory
import io.bluetape4k.leader.coroutines.SuspendLeaderElectorFactory
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElectorFactory
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.getBean
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import kotlin.time.Duration.Companion.seconds

class ConsulAopFactoryAutoConfigurationTest {

    companion object: KLogging()

    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(LeaderAopFactoryAutoConfiguration::class.java))
        .withUserConfiguration(ConsulEndpointConfig::class.java)
        .withPropertyValues(
            "bluetape4k.leader.consul.key-prefix=apps/orders/leader",
            "bluetape4k.leader.consul.session-name-prefix=orders-leader",
            "bluetape4k.leader.consul.lock-delay=2s",
        )

    @Test
    fun `ConsulEndpoint bean registers Consul AOP factories`() {
        runner.run { ctx ->
            ctx.getBean("consulLeaderElectionFactory").shouldBeInstanceOf<ConsulLeaderElectorFactory>()
            ctx.getBean("consulLeaderGroupElectionFactory").shouldBeInstanceOf<ConsulLeaderGroupElectorFactory>()
            ctx.getBean("consulSuspendLeaderElectorFactory").shouldBeInstanceOf<ConsulSuspendLeaderElectorFactory>()
            ctx.getBean("consulSuspendLeaderGroupElectorFactory").shouldBeInstanceOf<ConsulSuspendLeaderGroupElectorFactory>()

            ctx.getBean("consulLeaderElectionFactory").shouldBeInstanceOf<LeaderElectorFactory>()
            ctx.getBean("consulLeaderGroupElectionFactory").shouldBeInstanceOf<LeaderGroupElectorFactory>()
            ctx.getBean("consulSuspendLeaderElectorFactory").shouldBeInstanceOf<SuspendLeaderElectorFactory>()
            ctx.getBean("consulSuspendLeaderGroupElectorFactory").shouldBeInstanceOf<SuspendLeaderGroupElectorFactory>()

            val leaderFactory = ctx.getBean<ConsulLeaderElectorFactory>()
            val groupFactory = ctx.getBean<ConsulLeaderGroupElectorFactory>()
            val suspendFactory = ctx.getBean<ConsulSuspendLeaderElectorFactory>()
            val suspendGroupFactory = ctx.getBean<ConsulSuspendLeaderGroupElectorFactory>()

            leaderFactory.create(LeaderElectionOptions(leaseTime = 10.seconds))
                .shouldBeInstanceOf<ConsulLeaderElector>()
                .options.keyPrefix shouldBeEqualTo "apps/orders/leader"

            groupFactory.create(LeaderGroupElectionOptions(leaseTime = 10.seconds))
                .shouldBeInstanceOf<ConsulLeaderGroupElector>()
                .options.keyPrefix shouldBeEqualTo "apps/orders/leader"

            runSuspendIO {
                suspendFactory.create(LeaderElectionOptions(leaseTime = 10.seconds))
                    .shouldBeInstanceOf<ConsulSuspendLeaderElector>()
                    .options.keyPrefix shouldBeEqualTo "apps/orders/leader"

                suspendGroupFactory.create(LeaderGroupElectionOptions(leaseTime = 10.seconds))
                    .shouldBeInstanceOf<ConsulSuspendLeaderGroupElector>()
                    .options.keyPrefix shouldBeEqualTo "apps/orders/leader"
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    class ConsulEndpointConfig {
        @Bean
        fun consulEndpoint(): ConsulEndpoint = ConsulEndpoint("http://localhost:8500")
    }
}
