package io.bluetape4k.leader.spring.metrics

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.micrometer.MicrometerLeaderAopMetricsRecorder
import io.bluetape4k.leader.spring.aop.autoconfigure.LeaderAopAutoConfiguration
import io.bluetape4k.leader.spring.aop.autoconfigure.LeaderAopFactoryAutoConfiguration
import io.bluetape4k.logging.KLogging
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldNotBeNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.getBeansOfType
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.boot.health.contributor.Status
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LeaderMicrometerHealthAutoConfigurationTest {

    companion object : KLogging()

    private val runner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                LeaderAopFactoryAutoConfiguration::class.java,
                LeaderMicrometerAutoConfiguration::class.java,
                LeaderMicrometerHealthAutoConfiguration::class.java,
                LeaderAopAutoConfiguration::class.java,
            )
        )

    @Test
    fun `MicrometerLeaderAopMetricsRecorder 존재 시 leaderMetricsHealthIndicator 자동 등록`() {
        runner
            .withUserConfiguration(MeterRegistryConfig::class.java)
            .run { ctx ->
                ctx.getBean("leaderMetricsHealthIndicator", HealthIndicator::class.java).shouldNotBeNull()
            }
    }

    @Test
    fun `MicrometerLeaderAopMetricsRecorder 없을 때 HealthIndicator 미등록`() {
        runner.run { ctx ->
            ctx.getBeansOfType<HealthIndicator>().isEmpty() shouldBeEqualTo true
        }
    }

    @Test
    fun `metrics_enabled=false 시 HealthIndicator 미등록`() {
        runner
            .withUserConfiguration(MeterRegistryConfig::class.java)
            .withPropertyValues("bluetape4k.leader.aop.metrics.enabled=false")
            .run { ctx ->
                ctx.getBeansOfType<HealthIndicator>().isEmpty() shouldBeEqualTo true
            }
    }

    @Test
    fun `사용자 정의 leaderMetricsHealthIndicator 우선`() {
        runner
            .withUserConfiguration(MeterRegistryConfig::class.java, CustomHealthIndicatorConfig::class.java)
            .run { ctx ->
                val indicator = ctx.getBean("leaderMetricsHealthIndicator", HealthIndicator::class.java)
                indicator shouldBeInstanceOf CustomHealthIndicator::class
            }
    }

    @Test
    fun `health() 호출 시 UP 상태 및 active detail 포함`() {
        runner
            .withUserConfiguration(MeterRegistryConfig::class.java)
            .run { ctx ->
                val indicator = ctx.getBean("leaderMetricsHealthIndicator", HealthIndicator::class.java)
                val health = requireNotNull(indicator.health()) { "health() must not return null" }

                health.status shouldBeEqualTo Status.UP
                health.details["active"].shouldNotBeNull()
                health.details["trackedLocks"].shouldNotBeNull()
            }
    }

    @Test
    fun `active 작업 존재 시 active detail에 반영`() {
        runner
            .withUserConfiguration(MeterRegistryConfig::class.java)
            .run { ctx ->
                val recorder = ctx.getBean(MicrometerLeaderAopMetricsRecorder::class.java)
                val indicator = ctx.getBean("leaderMetricsHealthIndicator", HealthIndicator::class.java)

                recorder.onLockAttempt("job-lock", LeaderElectionOptions.Default)
                recorder.onLockAcquired("job-lock", LeaderElectionOptions.Default, kotlin.time.Duration.ZERO)
                recorder.onTaskStarted("job-lock")

                val health = requireNotNull(indicator.health()) { "health() must not return null" }
                health.status shouldBeEqualTo Status.UP
                (health.details["active"] as Int) shouldBeEqualTo 1
                (health.details["trackedLocks"] as Int) shouldBeEqualTo 1

                // 정리
                recorder.onTaskFinished("job-lock", kotlin.time.Duration.ZERO)
            }
    }

    @Test
    fun `active 작업 없을 때 active=0`() {
        runner
            .withUserConfiguration(MeterRegistryConfig::class.java)
            .run { ctx ->
                val indicator = ctx.getBean("leaderMetricsHealthIndicator", HealthIndicator::class.java)
                val health = requireNotNull(indicator.health()) { "health() must not return null" }

                health.status shouldBeEqualTo Status.UP
                (health.details["active"] as Int) shouldBeEqualTo 0
                (health.details["trackedLocks"] as Int) shouldBeEqualTo 0
            }
    }

    // ── Config helpers ──────────────────────────────────────────────

    @Configuration(proxyBeanMethods = false)
    class MeterRegistryConfig {
        @Bean
        fun simpleMeterRegistry(): SimpleMeterRegistry = SimpleMeterRegistry()
    }

    class CustomHealthIndicator : HealthIndicator {
        override fun health(): Health = Health.Builder().up().build()
    }

    @Configuration(proxyBeanMethods = false)
    class CustomHealthIndicatorConfig {
        @Bean("leaderMetricsHealthIndicator")
        fun leaderMetricsHealthIndicator(): HealthIndicator = CustomHealthIndicator()
    }
}
