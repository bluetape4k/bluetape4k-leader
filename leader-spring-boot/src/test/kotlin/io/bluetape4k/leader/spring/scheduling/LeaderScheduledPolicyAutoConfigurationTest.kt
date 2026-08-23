package io.bluetape4k.leader.spring.scheduling

import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.leader.spring.aop.autoconfigure.LeaderAopAutoConfiguration
import io.bluetape4k.leader.spring.aop.autoconfigure.LeaderAopFactoryAutoConfiguration
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled

class LeaderScheduledPolicyAutoConfigurationTest {

    private val runner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                LeaderAopFactoryAutoConfiguration::class.java,
                LeaderScheduledPolicyAutoConfiguration::class.java,
                LeaderAopAutoConfiguration::class.java,
            ),
        )
        .withUserConfiguration(ScheduledFixtureConfiguration::class.java)

    @Test
    fun `disabled by default does not create registry or policy BPP`() {
        runner.run { context ->
            context.startupFailure shouldBeEqualTo null
            context.containsBean("leaderScheduledPolicyRegistry").shouldBeFalse()
            context.containsBean("leaderScheduledPolicyBeanPostProcessor").shouldBeFalse()
        }
    }

    @Test
    fun `enabled policy creates registry and BPP`() {
        runner
            .withPropertyValues(
                "bluetape4k.leader.scheduling.enabled=true",
                "bluetape4k.leader.scheduling.policies[0].selector=scheduledFixture#reconcile",
                "bluetape4k.leader.scheduling.policies[0].name=orders:reconcile",
            )
            .run { context ->
                context.startupFailure shouldBeEqualTo null
                context.getBean(LeaderScheduledPolicyRegistry::class.java).shouldNotBeNull()
                context.getBean(LeaderScheduledPolicyBeanPostProcessor::class.java).shouldNotBeNull()
            }
    }

    @Test
    fun `enabled without policies fails startup`() {
        runner
            .withPropertyValues("bluetape4k.leader.scheduling.enabled=true")
            .run { context ->
                context.startupFailure.shouldNotBeNull()
                    .message.orEmpty() shouldContain "property 'policies'"
            }
    }

    @Test
    fun `missing selector fails startup with exact selector`() {
        runner
            .withPropertyValues(
                "bluetape4k.leader.scheduling.enabled=true",
                "bluetape4k.leader.scheduling.policies[0].selector=scheduledFixture#missing",
                "bluetape4k.leader.scheduling.policies[0].name=missing",
            )
            .run { context ->
                context.startupFailure.shouldNotBeNull()
                    .message.orEmpty() shouldContain "scheduledFixture#missing"
            }
    }

    @Test
    fun `non scheduled selector fails startup with exact selector`() {
        runner
            .withPropertyValues(
                "bluetape4k.leader.scheduling.enabled=true",
                "bluetape4k.leader.scheduling.policies[0].selector=scheduledFixture#notScheduled",
                "bluetape4k.leader.scheduling.policies[0].name=not-scheduled",
            )
            .run { context ->
                context.startupFailure.shouldNotBeNull()
                    .message.orEmpty() shouldContain "scheduledFixture#notScheduled"
            }
    }

    @Test
    fun `imports place policy auto configuration between factory and AOP`() {
        val imports = javaClass.classLoader
            .getResource("META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")
            .shouldNotBeNull()
            .readText()
            .lines()

        val factory = imports.indexOf(LeaderAopFactoryAutoConfiguration::class.qualifiedName)
        val policy = imports.indexOf(LeaderScheduledPolicyAutoConfiguration::class.qualifiedName)
        val aop = imports.indexOf(LeaderAopAutoConfiguration::class.qualifiedName)

        (factory >= 0).shouldBeTrue()
        (policy > factory).shouldBeTrue()
        (aop > policy).shouldBeTrue()
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    class ScheduledFixtureConfiguration {

        @Bean("scheduledFixture")
        fun scheduledFixture(): ScheduledFixture = ScheduledFixture()
    }

    class ScheduledFixture {
        @Scheduled(fixedDelay = Long.MAX_VALUE, initialDelay = Long.MAX_VALUE)
        fun reconcile() = Unit

        fun notScheduled() = Unit
    }
}
