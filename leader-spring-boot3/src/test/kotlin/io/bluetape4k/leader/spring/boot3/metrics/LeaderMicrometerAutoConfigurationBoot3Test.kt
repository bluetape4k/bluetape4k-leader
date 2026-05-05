package io.bluetape4k.leader.spring.boot3.metrics

import io.bluetape4k.leader.LeaderElection as CoreLeaderElection
import io.bluetape4k.leader.LeaderElectionFactory
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.micrometer.MicrometerLeaderAopMetricsRecorder
import io.bluetape4k.leader.spring.aop.LeaderAspectFailureMode
import io.bluetape4k.leader.spring.aop.LeaderElection
import io.bluetape4k.leader.spring.aop.metrics.LeaderAopMetricsRecorder
import io.bluetape4k.leader.spring.aop.metrics.SkipReason
import io.bluetape4k.leader.spring.boot3.aop.autoconfigure.LeaderAopAutoConfiguration
import io.bluetape4k.leader.spring.boot3.aop.autoconfigure.LeaderAopFactoryAutoConfiguration
import io.bluetape4k.logging.KLogging
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeGreaterOrEqualTo
import org.amshove.kluent.shouldBeInstanceOf
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.context.annotation.Primary
import org.springframework.beans.factory.getBeansOfType
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.boot.actuate.health.Status
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.CompletableFuture

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LeaderMicrometerAutoConfigurationBoot3Test {

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
                // MicrometerLeaderAopMetricsRecorder 미등록 → HealthContributor도 미등록
                ctx.containsBean("leaderMicrometerHealthContributor") shouldBeEqualTo false
            }
    }

    @Test
    fun `LeaderElectionAspect 통과 시 attempts+acquired+timer+active 전체 검증`() {
        runner
            .withUserConfiguration(MeterRegistryConfig::class.java, TestLeaderServiceConfig::class.java)
            .withPropertyValues("bluetape4k.leader.aop.lock-name-prefix=")
            .run { ctx ->
                val service = ctx.getBean(TestLeaderService::class.java)
                val registry = ctx.getBean(SimpleMeterRegistry::class.java)

                service.doWork()

                // LocalLeaderElection 은 항상 성공하므로 attempts + acquired 모두 증가
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
            .withUserConfiguration(
                MeterRegistryConfig::class.java,
                ThrowingFactoryConfig::class.java,
                SkipModeServiceConfig::class.java,
            )
            .withPropertyValues("bluetape4k.leader.aop.lock-name-prefix=")
            .run { ctx ->
                val service = ctx.getBean(SkipModeService::class.java)
                val registry = ctx.getBean(SimpleMeterRegistry::class.java)

                // SKIP 모드: backend 예외 흡수 후 null 반환 → onLockNotAcquired(BACKEND_ERROR)
                service.doSkipWork()

                registry.get("leader.aop.lock.not.acquired")
                    .tag("lock.name", "skip-lock")
                    .tag("reason", "BACKEND_ERROR")
                    .counter().count() shouldBeGreaterOrEqualTo 1.0
            }
    }

    @Test
    fun `leaderMicrometerHealthContributor 빈 등록 검증`() {
        runner
            .withUserConfiguration(MeterRegistryConfig::class.java)
            .run { ctx ->
                val healthIndicator = ctx.getBean(
                    "leaderMicrometerHealthContributor",
                    HealthIndicator::class.java,
                )
                healthIndicator.shouldNotBeNull()
                val health = healthIndicator.health()
                health.status shouldBeEqualTo Status.UP
                health.details.containsKey("metrics.registered").shouldBeTrue()
                health.details.containsKey("attempts.total").shouldBeTrue()
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
    class TestLeaderServiceConfig {
        @Bean
        fun testLeaderService(): TestLeaderService = TestLeaderService()
    }

    open class TestLeaderService {
        @LeaderElection(name = "test-lock")
        open fun doWork(): String? = "done"
    }

    @Configuration(proxyBeanMethods = false)
    class ThrowingFactoryConfig {
        @Bean
        @Primary
        fun throwingLeaderElectionFactory(): LeaderElectionFactory = LeaderElectionFactory { _ ->
            object : CoreLeaderElection {
                override fun <T> runIfLeader(lockName: String, action: () -> T): T? {
                    throw RuntimeException("Simulated backend error")
                }

                override fun <T> runAsyncIfLeader(
                    lockName: String,
                    executor: java.util.concurrent.Executor,
                    action: () -> CompletableFuture<T>,
                ): CompletableFuture<T?> =
                    CompletableFuture.failedFuture(RuntimeException("Simulated backend error"))
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    class SkipModeServiceConfig {
        @Bean
        fun skipModeService(): SkipModeService = SkipModeService()
    }

    open class SkipModeService {
        @LeaderElection(name = "skip-lock", failureMode = LeaderAspectFailureMode.SKIP)
        open fun doSkipWork(): String? = "done"
    }
}
