package io.bluetape4k.leader.spring.aop.autoconfigure

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderElectorFactory
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LeaderGroupElectorFactory
import io.bluetape4k.leader.coroutines.SuspendLeaderElectorFactory
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElectorFactory
import io.bluetape4k.leader.etcd.EtcdLeaderElector
import io.bluetape4k.leader.etcd.EtcdLeaderElectorFactory
import io.bluetape4k.leader.etcd.EtcdLeaderGroupElector
import io.bluetape4k.leader.etcd.EtcdLeaderGroupElectorFactory
import io.bluetape4k.leader.etcd.EtcdSuspendLeaderElector
import io.bluetape4k.leader.etcd.EtcdSuspendLeaderElectorFactory
import io.bluetape4k.leader.etcd.EtcdSuspendLeaderGroupElector
import io.bluetape4k.leader.etcd.EtcdSuspendLeaderGroupElectorFactory
import io.etcd.jetcd.Client
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

class EtcdAopFactoryAutoConfigurationTest {

    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(LeaderAopFactoryAutoConfiguration::class.java))
        .withUserConfiguration(EtcdClientConfig::class.java)
        .withPropertyValues("bluetape4k.leader.etcd.key-prefix=/apps/orders/leader")

    @Test
    fun `jetcd Client bean registers etcd AOP factories`() {
        runner.run { ctx ->
            ctx.getBean("etcdLeaderElectionFactory").shouldBeInstanceOf<EtcdLeaderElectorFactory>()
            ctx.getBean("etcdLeaderGroupElectionFactory").shouldBeInstanceOf<EtcdLeaderGroupElectorFactory>()
            ctx.getBean("etcdSuspendLeaderElectorFactory").shouldBeInstanceOf<EtcdSuspendLeaderElectorFactory>()
            ctx.getBean("etcdSuspendLeaderGroupElectorFactory").shouldBeInstanceOf<EtcdSuspendLeaderGroupElectorFactory>()

            ctx.getBean("etcdLeaderElectionFactory").shouldBeInstanceOf<LeaderElectorFactory>()
            ctx.getBean("etcdLeaderGroupElectionFactory").shouldBeInstanceOf<LeaderGroupElectorFactory>()
            ctx.getBean("etcdSuspendLeaderElectorFactory").shouldBeInstanceOf<SuspendLeaderElectorFactory>()
            ctx.getBean("etcdSuspendLeaderGroupElectorFactory").shouldBeInstanceOf<SuspendLeaderGroupElectorFactory>()

            val leaderFactory = ctx.getBean(EtcdLeaderElectorFactory::class.java)
            val groupFactory = ctx.getBean(EtcdLeaderGroupElectorFactory::class.java)
            val suspendFactory = ctx.getBean(EtcdSuspendLeaderElectorFactory::class.java)
            val suspendGroupFactory = ctx.getBean(EtcdSuspendLeaderGroupElectorFactory::class.java)

            leaderFactory.create(LeaderElectionOptions.Default)
                .shouldBeInstanceOf<EtcdLeaderElector>()
                .options.keyPrefix shouldBeEqualTo "/apps/orders/leader"

            groupFactory.create(LeaderGroupElectionOptions.Default)
                .shouldBeInstanceOf<EtcdLeaderGroupElector>()
                .options.keyPrefix shouldBeEqualTo "/apps/orders/leader"

            runBlocking {
                suspendFactory.create(LeaderElectionOptions.Default)
                    .shouldBeInstanceOf<EtcdSuspendLeaderElector>()
                    .options.keyPrefix shouldBeEqualTo "/apps/orders/leader"

                suspendGroupFactory.create(LeaderGroupElectionOptions.Default)
                    .shouldBeInstanceOf<EtcdSuspendLeaderGroupElector>()
                    .options.keyPrefix shouldBeEqualTo "/apps/orders/leader"
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    class EtcdClientConfig {
        @Bean
        fun etcdClient(): Client = mockk(relaxed = true)
    }
}
