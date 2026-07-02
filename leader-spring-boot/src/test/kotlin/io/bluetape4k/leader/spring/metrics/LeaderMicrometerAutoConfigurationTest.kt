package io.bluetape4k.leader.spring.metrics

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.micrometer.LeaderMetricTagSanitizer
import io.bluetape4k.leader.micrometer.MicrometerLeaderAopMetricsRecorder
import io.bluetape4k.leader.metrics.LeaderAopMetricsRecorder
import io.bluetape4k.leader.metrics.SkipReason
import io.bluetape4k.leader.spring.aop.autoconfigure.LeaderAopAutoConfiguration
import io.bluetape4k.leader.spring.aop.autoconfigure.LeaderAopFactoryAutoConfiguration
import io.bluetape4k.logging.KLogging
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterOrEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.getBeansOfType
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import kotlin.time.Duration.Companion.milliseconds

/**
 * `LeaderMicrometerAutoConfiguration` 통합 테스트.
 *
 * Boot는 Freefair AspectJ post-compile weaving을 사용한다.
 * `@LeaderElection`-annotated 메서드는 컴파일 시 advice가 woven되어
 * Spring AOP runtime proxy와 함께 사용하면 double-advice 위험이 있다.
 * 따라서 테스트 5/6은 AOP proxy를 거치지 않고 recorder를 직접 호출하여 메트릭을 검증한다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LeaderMicrometerAutoConfigurationTest {

    companion object : KLogging()

    private val runner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                LeaderAopFactoryAutoConfiguration::class.java,
                LeaderMicrometerAutoConfiguration::class.java,
                LeaderAopAutoConfiguration::class.java,
            )
        )

    @Test
    fun `MeterRegistry 빈 존재 시 MicrometerLeaderAopMetricsRecorder 자동 등록`() {
        runner
            .withUserConfiguration(MeterRegistryConfig::class.java)
            .run { ctx ->
                ctx.getBean(MicrometerLeaderAopMetricsRecorder::class.java).shouldNotBeNull()
            }
    }

    @Test
    fun `MeterRegistry 빈 없을 때 recorder 빈 미등록`() {
        runner.run { ctx ->
            ctx.getBeansOfType<LeaderAopMetricsRecorder>().isEmpty().shouldBeTrue()
        }
    }

    @Test
    fun `enabled=false 시 빈 미등록`() {
        runner
            .withUserConfiguration(MeterRegistryConfig::class.java)
            .withPropertyValues("bluetape4k.leader.aop.metrics.enabled=false")
            .run { ctx ->
                ctx.getBeansOfType<LeaderAopMetricsRecorder>().isEmpty().shouldBeTrue()
            }
    }

    @Test
    fun `사용자 정의 LeaderAopMetricsRecorder가 우선`() {
        runner
            .withUserConfiguration(MeterRegistryConfig::class.java, CustomRecorderConfig::class.java)
            .run { ctx ->
                ctx.getBeansOfType<MicrometerLeaderAopMetricsRecorder>().isEmpty().shouldBeTrue()
                ctx.getBean(LeaderAopMetricsRecorder::class.java) shouldBeInstanceOf LeaderAopMetricsRecorder.NoOp::class
            }
    }

    @Test
    fun `recorder 콜백 호출 시 attempts+acquired+timer+active 전체 검증`() {
        runner
            .withUserConfiguration(MeterRegistryConfig::class.java)
            .withPropertyValues("bluetape4k.leader.aop.metrics.tags.lock-name.mode=RAW")
            .run { ctx ->
                val recorder = ctx.getBean(MicrometerLeaderAopMetricsRecorder::class.java)
                val registry = ctx.getBean(SimpleMeterRegistry::class.java)
                val options = LeaderElectionOptions.Default

                recorder.onLockAttempt("test-lock", options)
                recorder.onLockAcquired("test-lock", options, 5.milliseconds)
                recorder.onTaskStarted("test-lock")
                recorder.onTaskFinished("test-lock", 100.milliseconds)

                registry.get("leader.aop.attempts")
                    .tag("lock.name", "test-lock")
                    .counter().count() shouldBeGreaterOrEqualTo 1.0
                registry.get("leader.aop.acquired")
                    .tag("lock.name", "test-lock")
                    .counter().count() shouldBeGreaterOrEqualTo 1.0
                registry.get("leader.aop.execution.duration")
                    .tag("lock.name", "test-lock")
                    .timer().count() shouldBeGreaterOrEqualTo 1L
                registry.find("leader.aop.active")
                    .tag("lock.name", "test-lock")
                    .gauge()?.value() shouldBeEqualTo 0.0
            }
    }

    @Test
    fun `backend 예외 발생 시 lock_not_acquired reason=BACKEND_ERROR 메트릭 증가`() {
        runner
            .withUserConfiguration(MeterRegistryConfig::class.java)
            .withPropertyValues("bluetape4k.leader.aop.metrics.tags.lock-name.mode=RAW")
            .run { ctx ->
                val recorder = ctx.getBean(MicrometerLeaderAopMetricsRecorder::class.java)
                val registry = ctx.getBean(SimpleMeterRegistry::class.java)

                recorder.onLockNotAcquired("test-lock", LeaderElectionOptions.Default, SkipReason.BACKEND_ERROR)

                registry.get("leader.aop.lock.not.acquired")
                    .tag("lock.name", "test-lock")
                    .tag("reason", "BACKEND_ERROR")
                    .counter().count() shouldBeEqualTo 1.0
            }
    }

    @Test
    fun `default Spring recorder redacts lock name tags`() {
        runner
            .withUserConfiguration(MeterRegistryConfig::class.java)
            .run { ctx ->
                val recorder = ctx.getBean(MicrometerLeaderAopMetricsRecorder::class.java)
                val registry = ctx.getBean(SimpleMeterRegistry::class.java)

                recorder.onLockAttempt("tenant-a", LeaderElectionOptions.Default)
                recorder.onLockAttempt("tenant-b", LeaderElectionOptions.Default)

                registry.get("leader.aop.attempts")
                    .tag("lock.name", "redacted-lock")
                    .counter().count() shouldBeEqualTo 2.0
            }
    }

    @Test
    fun `custom LeaderMetricTagSanitizer bean overrides bound properties`() {
        runner
            .withUserConfiguration(MeterRegistryConfig::class.java, CustomSanitizerConfig::class.java)
            .withPropertyValues("bluetape4k.leader.aop.metrics.tags.lock-name.mode=RAW")
            .run { ctx ->
                val recorder = ctx.getBean(MicrometerLeaderAopMetricsRecorder::class.java)
                val registry = ctx.getBean(SimpleMeterRegistry::class.java)

                recorder.onLockAttempt("tenant-a", LeaderElectionOptions.Default)

                registry.get("leader.aop.attempts")
                    .tag("lock.name", "custom-tag")
                    .counter().count() shouldBeEqualTo 1.0
            }
    }

    @Test
    fun `spring boot context starts when leader micrometer classes are absent`() {
        runner
            .withClassLoader(FilteredClassLoader("io.bluetape4k.leader.micrometer"))
            .run { ctx ->
                ctx.startupFailure shouldBeEqualTo null
                ctx.getBeansOfType<LeaderAopMetricsRecorder>().isEmpty().shouldBeTrue()
            }
    }

    // ── Config helpers ──────────────────────────────────────────────

    @Configuration(proxyBeanMethods = false)
    class MeterRegistryConfig {
        @Bean
        fun simpleMeterRegistry(): SimpleMeterRegistry = SimpleMeterRegistry()
    }

    @Configuration(proxyBeanMethods = false)
    class CustomRecorderConfig {
        @Bean
        fun customRecorder(): LeaderAopMetricsRecorder = LeaderAopMetricsRecorder.NoOp
    }

    @Configuration(proxyBeanMethods = false)
    class CustomSanitizerConfig {
        @Bean
        fun customSanitizer(): LeaderMetricTagSanitizer =
            LeaderMetricTagSanitizer { _, _ -> "custom-tag" }
    }
}
