package io.bluetape4k.leader.spring.metrics

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.leader.LeaderElectionListener
import io.bluetape4k.leader.metrics.LeaderAopMetricsRecorder
import io.bluetape4k.leader.micrometer.MicrometerLeaderAopMetricsRecorder
import io.bluetape4k.leader.micrometer.MicrometerObservationLeaderAopMetricsRecorder
import io.bluetape4k.leader.micrometer.MicrometerObservationLeaderElectionListener
import io.bluetape4k.leader.spring.aop.autoconfigure.LeaderAopAutoConfiguration
import io.bluetape4k.leader.spring.aop.autoconfigure.LeaderAopFactoryAutoConfiguration
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.observation.ObservationRegistry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.getBeansOfType
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LeaderObservationAutoConfigurationTest {

    private val runner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                LeaderAopFactoryAutoConfiguration::class.java,
                LeaderMicrometerAutoConfiguration::class.java,
                LeaderObservationAutoConfiguration::class.java,
                LeaderAopAutoConfiguration::class.java,
            )
        )

    @Test
    fun `ObservationRegistry 빈 존재 시 Observation recorder 와 listener 자동 등록`() {
        runner
            .withUserConfiguration(ObservationRegistryConfig::class.java)
            .run { ctx ->
                ctx.getBean(MicrometerObservationLeaderAopMetricsRecorder::class.java).shouldNotBeNull()
                ctx.getBean(MicrometerObservationLeaderElectionListener::class.java).shouldNotBeNull()
                ctx.getBean(LeaderElectionListener::class.java)
                    .shouldBeInstanceOf<MicrometerObservationLeaderElectionListener>()
            }
    }

    @Test
    fun `ObservationRegistry 빈 없을 때 Observation recorder 와 listener 미등록`() {
        runner.run { ctx ->
            ctx.getBeansOfType<MicrometerObservationLeaderAopMetricsRecorder>().isEmpty().shouldBeTrue()
            ctx.getBeansOfType<MicrometerObservationLeaderElectionListener>().isEmpty().shouldBeTrue()
        }
    }

    @Test
    fun `observability enabled=false 시 tracing 빈도 미등록`() {
        runner
            .withUserConfiguration(ObservationRegistryConfig::class.java)
            .withPropertyValues("bluetape4k.leader.observability.enabled=false")
            .run { ctx ->
                ctx.getBeansOfType<MicrometerObservationLeaderAopMetricsRecorder>().isEmpty().shouldBeTrue()
                ctx.getBeansOfType<MicrometerObservationLeaderElectionListener>().isEmpty().shouldBeTrue()
            }
    }

    @Test
    fun `tracing enabled=false 시 Observation recorder 와 listener 미등록`() {
        runner
            .withUserConfiguration(ObservationRegistryConfig::class.java)
            .withPropertyValues("bluetape4k.leader.observability.tracing.enabled=false")
            .run { ctx ->
                ctx.getBeansOfType<MicrometerObservationLeaderAopMetricsRecorder>().isEmpty().shouldBeTrue()
                ctx.getBeansOfType<MicrometerObservationLeaderElectionListener>().isEmpty().shouldBeTrue()
            }
    }

    @Test
    fun `tracing option properties bind to Observation recorder and listener`() {
        runner
            .withUserConfiguration(ObservationRegistryConfig::class.java)
            .withPropertyValues(
                "bluetape4k.leader.observability.tracing.include-lock-name=true",
                "bluetape4k.leader.observability.tracing.include-leader-id=true",
                "bluetape4k.leader.observability.tracing.include-exception-details=true",
            )
            .run { ctx ->
                val recorder = ctx.getBean(MicrometerObservationLeaderAopMetricsRecorder::class.java)
                val listener = ctx.getBean(MicrometerObservationLeaderElectionListener::class.java)

                recorder.options.includeLockName.shouldBeTrue()
                recorder.options.includeLeaderId.shouldBeTrue()
                recorder.options.includeExceptionDetails.shouldBeTrue()
                listener.options shouldBeEqualTo recorder.options
            }
    }

    @Test
    fun `MeterRegistry 와 ObservationRegistry 가 함께 있으면 metrics 와 tracing recorder 공존`() {
        runner
            .withUserConfiguration(MeterRegistryConfig::class.java, ObservationRegistryConfig::class.java)
            .run { ctx ->
                ctx.getBean(MicrometerLeaderAopMetricsRecorder::class.java).shouldNotBeNull()
                ctx.getBean(MicrometerObservationLeaderAopMetricsRecorder::class.java).shouldNotBeNull()
            }
    }

    @Test
    fun `사용자 정의 Observation recorder 는 default meter recorder 를 억제하지 않는다`() {
        runner
            .withUserConfiguration(MeterRegistryConfig::class.java, CustomObservationRecorderConfig::class.java)
            .run { ctx ->
                ctx.getBean(MicrometerLeaderAopMetricsRecorder::class.java).shouldNotBeNull()
                ctx.getBean(MicrometerObservationLeaderAopMetricsRecorder::class.java)
                    .shouldBeInstanceOf<MicrometerObservationLeaderAopMetricsRecorder>()
            }
    }

    @Test
    fun `사용자 정의 generic LeaderAopMetricsRecorder 는 default meter recorder 를 억제한다`() {
        runner
            .withUserConfiguration(MeterRegistryConfig::class.java, ObservationRegistryConfig::class.java, CustomGenericRecorderConfig::class.java)
            .run { ctx ->
                ctx.getBeansOfType<MicrometerLeaderAopMetricsRecorder>().isEmpty().shouldBeTrue()
                ctx.getBeansOfType<LeaderAopMetricsRecorder>()["customRecorder"] shouldBeEqualTo LeaderAopMetricsRecorder.NoOp
                ctx.getBean(MicrometerObservationLeaderAopMetricsRecorder::class.java).shouldNotBeNull()
            }
    }

    @Test
    fun `사용자 정의 Observation recorder 가 자동 등록 recorder 보다 우선한다`() {
        runner
            .withUserConfiguration(CustomObservationRecorderConfig::class.java)
            .run { ctx ->
                val recorders = ctx.getBeansOfType<MicrometerObservationLeaderAopMetricsRecorder>()

                recorders.size shouldBeEqualTo 1
                recorders.values.single().options.includeLockName.shouldBeTrue()
            }
    }

    @Test
    fun `AutoConfiguration imports 는 metrics 다음 observation 그리고 AOP 순서를 유지한다`() {
        val imports = javaClass.classLoader
            .getResource("META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")
            .shouldNotBeNull()
            .readText()
            .lines()

        val metricsIndex = imports.indexOf(LeaderMicrometerAutoConfiguration::class.qualifiedName)
        val observationIndex = imports.indexOf(LeaderObservationAutoConfiguration::class.qualifiedName)
        val aopIndex = imports.indexOf(LeaderAopAutoConfiguration::class.qualifiedName)

        (metricsIndex >= 0).shouldBeTrue()
        (observationIndex > metricsIndex).shouldBeTrue()
        (aopIndex > observationIndex).shouldBeTrue()
    }

    @Configuration(proxyBeanMethods = false)
    class MeterRegistryConfig {
        @Bean
        fun simpleMeterRegistry(): SimpleMeterRegistry = SimpleMeterRegistry()
    }

    @Configuration(proxyBeanMethods = false)
    class ObservationRegistryConfig {
        @Bean
        fun observationRegistry(): ObservationRegistry = ObservationRegistry.create()
    }

    @Configuration(proxyBeanMethods = false)
    class CustomObservationRecorderConfig {
        @Bean
        fun observationRegistry(): ObservationRegistry = ObservationRegistry.create()

        @Bean
        fun customObservationRecorder(registry: ObservationRegistry): MicrometerObservationLeaderAopMetricsRecorder =
            MicrometerObservationLeaderAopMetricsRecorder(
                registry = registry,
                options = io.bluetape4k.leader.micrometer.LeaderObservationOptions(includeLockName = true),
            )
    }

    @Configuration(proxyBeanMethods = false)
    class CustomGenericRecorderConfig {
        @Bean
        fun customRecorder(): LeaderAopMetricsRecorder = LeaderAopMetricsRecorder.NoOp
    }
}
