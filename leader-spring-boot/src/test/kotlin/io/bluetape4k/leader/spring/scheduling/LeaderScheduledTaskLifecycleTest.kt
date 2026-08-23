package io.bluetape4k.leader.spring.scheduling

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.leader.spring.aop.autoconfigure.LeaderAopAutoConfiguration
import io.bluetape4k.leader.spring.aop.autoconfigure.LeaderAopFactoryAutoConfiguration
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationHandler
import io.micrometer.observation.ObservationRegistry
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.getBeansOfType
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.annotation.SchedulingConfigurer
import org.springframework.scheduling.config.ScheduledTaskHolder
import org.springframework.scheduling.config.ScheduledTaskRegistrar
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class LeaderScheduledTaskLifecycleTest {

    private val runner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                LeaderAopFactoryAutoConfiguration::class.java,
                LeaderScheduledPolicyAutoConfiguration::class.java,
                LeaderAopAutoConfiguration::class.java,
            ),
        )

    @Test
    fun `property policy preserves one Spring scheduled task and context close cancels it`() {
        runner.withUserConfiguration(SchedulingConfiguration::class.java).run { context ->
            scheduledTasks(context).size shouldBeEqualTo 1
        }

        runner
            .withUserConfiguration(SchedulingConfiguration::class.java)
            .withPropertyValues(
                "bluetape4k.leader.scheduling.enabled=true",
                "bluetape4k.leader.scheduling.policies[0].selector=scheduledLifecycleFixture#reconcile",
                "bluetape4k.leader.scheduling.policies[0].name=scheduled-lifecycle",
            )
            .run { context ->
                scheduledTasks(context).size shouldBeEqualTo 1
                val holder = context.getBeansOfType<ScheduledTaskHolder>().values.single()
                context.close()
                holder.scheduledTasks.size shouldBeEqualTo 0
            }
    }

    @Test
    fun `property policy keeps one scheduled Observation per invocation`() {
        val handler = CountingObservationHandler()
        val latch = CountDownLatch(1)
        LifecycleState.handler = handler
        LifecycleState.latch = latch
        try {
            runner
                .withUserConfiguration(ObservationSchedulingConfiguration::class.java)
                .withPropertyValues(
                    "bluetape4k.leader.scheduling.enabled=true",
                    "bluetape4k.leader.scheduling.policies[0].selector=observationLifecycleFixture#observe",
                    "bluetape4k.leader.scheduling.policies[0].name=observation-lifecycle",
                )
                .run { context ->
                    latch.await(2, TimeUnit.SECONDS).shouldBeTrue()
                    handler.starts.get() shouldBeEqualTo 1
                    scheduledTasks(context).size shouldBeEqualTo 1
                }
        } finally {
            LifecycleState.handler = null
            LifecycleState.latch = null
        }
    }

    private fun scheduledTasks(context: org.springframework.context.ConfigurableApplicationContext) =
        context.getBeansOfType<ScheduledTaskHolder>().values
            .flatMap { it.scheduledTasks }
            .toSet()

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    class SchedulingConfiguration {

        @Bean("scheduledLifecycleFixture")
        fun scheduledLifecycleFixture(): ScheduledLifecycleFixture = ScheduledLifecycleFixture()
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    class ObservationSchedulingConfiguration {

        @Bean
        fun observationRegistry(): ObservationRegistry = ObservationRegistry.create().apply {
            observationConfig().observationHandler(checkNotNull(LifecycleState.handler))
        }

        @Bean
        fun schedulingConfigurer(registry: ObservationRegistry): SchedulingConfigurer =
            SchedulingConfigurer { registrar: ScheduledTaskRegistrar ->
                registrar.setObservationRegistry(registry)
            }

        @Bean("observationLifecycleFixture")
        fun observationLifecycleFixture(): ObservationLifecycleFixture =
            ObservationLifecycleFixture(checkNotNull(LifecycleState.latch))
    }

    class ScheduledLifecycleFixture {

        @Scheduled(fixedDelay = 50, initialDelay = 60_000)
        fun reconcile() = Unit
    }

    class ObservationLifecycleFixture(
        private val latch: CountDownLatch,
    ) {

        @Scheduled(fixedDelay = 1_000, initialDelay = 0)
        fun observe() {
            latch.countDown()
        }
    }

    private class CountingObservationHandler : ObservationHandler<Observation.Context> {
        val starts = AtomicInteger()

        override fun supportsContext(context: Observation.Context): Boolean = true

        override fun onStart(context: Observation.Context) {
            starts.incrementAndGet()
        }
    }

    private object LifecycleState {
        var handler: CountingObservationHandler? = null
        var latch: CountDownLatch? = null
    }
}
