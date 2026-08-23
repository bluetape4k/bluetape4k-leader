package io.bluetape4k.leader.spring.metrics

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.leader.LeaderElectionListener
import io.bluetape4k.leader.metrics.LeaderAopMetricsRecorder
import io.bluetape4k.leader.micrometer.LeaderMetricTagMode
import io.bluetape4k.leader.micrometer.MicrometerLeaderAopMetricsRecorder
import io.bluetape4k.leader.micrometer.MicrometerObservationLeaderAopMetricsRecorder
import io.bluetape4k.leader.micrometer.MicrometerObservationLeaderElectionListener
import io.bluetape4k.leader.spring.aop.autoconfigure.LeaderAopAutoConfiguration
import io.bluetape4k.leader.spring.aop.autoconfigure.LeaderAopFactoryAutoConfiguration
import io.bluetape4k.leader.spring.scheduling.LeaderScheduledPolicyAutoConfiguration
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationHandler
import io.micrometer.observation.ObservationRegistry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.getBeansOfType
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LeaderObservationAutoConfigurationTest {

    private val runner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                LeaderAopFactoryAutoConfiguration::class.java,
                LeaderMicrometerAutoConfiguration::class.java,
                LeaderObservationAutoConfiguration::class.java,
                LeaderScheduledPolicyAutoConfiguration::class.java,
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
    fun `정상 ObservationRegistry 는 context 소유 lease-extension registration handle 을 등록한다`() {
        runner
            .withUserConfiguration(ObservationRegistryConfig::class.java)
            .run { ctx ->
                ctx.containsBean("leaseExtensionObserverRegistration").shouldBeTrue()
                ctx.getBean("leaseExtensionObserverRegistration")
                    .shouldBeInstanceOf<AutoCloseable>()
                LeaseExtensionObservationRegistrationManager.registryCount() shouldBeEqualTo 1
            }

        LeaseExtensionObservationRegistrationManager.registryCount() shouldBeEqualTo 0
    }

    @Test
    fun `NOOP ObservationRegistry 는 lease-extension registration bean 을 만들지 않는다`() {
        runner
            .withUserConfiguration(NoopObservationRegistryConfig::class.java)
            .run { ctx ->
                ctx.containsBean("leaseExtensionObserverRegistration").shouldBeFalse()
                LeaseExtensionObservationRegistrationManager.registryCount() shouldBeEqualTo 0
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
    fun `Boot ObservationRegistry post-processor 가 handler 를 적용한 뒤 registration 한다`() {
        runner
            .withConfiguration(AutoConfigurations.of(ObservationAutoConfiguration::class.java))
            .withUserConfiguration(BootObservationHandlerConfig::class.java)
            .run { ctx ->
                ctx.getBean(ObservationRegistry::class.java).isNoop.shouldBeFalse()
                ctx.containsBean("leaseExtensionObserverRegistration").shouldBeTrue()
                LeaseExtensionObservationRegistrationManager.registryCount() shouldBeEqualTo 1
            }

        LeaseExtensionObservationRegistrationManager.registryCount() shouldBeEqualTo 0
    }

    @Test
    fun `primary ObservationRegistry 가 여러 registry 중 lease-extension registration 대상이 된다`() {
        runner
            .withUserConfiguration(MultipleObservationRegistryConfig::class.java)
            .run { ctx ->
                val primary = ctx.getBean(ObservationRegistry::class.java)
                ctx.containsBean("leaseExtensionObserverRegistration").shouldBeTrue()
                LeaseExtensionObservationRegistrationManager.referenceCount(primary) shouldBeEqualTo 1
                LeaseExtensionObservationRegistrationManager.registryCount() shouldBeEqualTo 1
            }

        LeaseExtensionObservationRegistrationManager.registryCount() shouldBeEqualTo 0
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
                "bluetape4k.leader.aop.metrics.tags.lock-name.mode=HASH",
                "bluetape4k.leader.aop.metrics.tags.lock-name.hash-length=12",
            )
            .run { ctx ->
                val recorder = ctx.getBean(MicrometerObservationLeaderAopMetricsRecorder::class.java)
                val listener = ctx.getBean(MicrometerObservationLeaderElectionListener::class.java)

                recorder.options.includeLockName.shouldBeTrue()
                recorder.options.includeLeaderId.shouldBeTrue()
                recorder.options.includeExceptionDetails.shouldBeTrue()
                recorder.options.tagOptions.lockName.mode shouldBeEqualTo LeaderMetricTagMode.HASH
                recorder.options.tagOptions.lockName.hashLength shouldBeEqualTo 12
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

        val factoryIndex = imports.indexOf(LeaderAopFactoryAutoConfiguration::class.qualifiedName)
        val metricsIndex = imports.indexOf(LeaderMicrometerAutoConfiguration::class.qualifiedName)
        val observationIndex = imports.indexOf(LeaderObservationAutoConfiguration::class.qualifiedName)
        val schedulingPolicyIndex = imports.indexOf(LeaderScheduledPolicyAutoConfiguration::class.qualifiedName)
        val aopIndex = imports.indexOf(LeaderAopAutoConfiguration::class.qualifiedName)

        (factoryIndex >= 0).shouldBeTrue()
        (schedulingPolicyIndex > factoryIndex).shouldBeTrue()
        (schedulingPolicyIndex < metricsIndex).shouldBeTrue()
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
        fun observationRegistry(): ObservationRegistry = ObservationRegistry.create().apply {
            observationConfig().observationHandler(NonNoopObservationHandler)
        }
    }

    @Configuration(proxyBeanMethods = false)
    class NoopObservationRegistryConfig {
        @Bean
        fun observationRegistry(): ObservationRegistry = ObservationRegistry.NOOP
    }

    @Configuration(proxyBeanMethods = false)
    class BootObservationHandlerConfig {
        @Bean
        fun observationHandler(): ObservationHandler<Observation.Context> = NonNoopObservationHandler
    }

    @Configuration(proxyBeanMethods = false)
    class MultipleObservationRegistryConfig {
        @Bean
        @Primary
        fun primaryObservationRegistry(): ObservationRegistry = ObservationRegistry.create().apply {
            observationConfig().observationHandler(NonNoopObservationHandler)
        }

        @Bean
        fun secondaryObservationRegistry(): ObservationRegistry = ObservationRegistry.create().apply {
            observationConfig().observationHandler(NonNoopObservationHandler)
        }
    }

    private object NonNoopObservationHandler : ObservationHandler<Observation.Context> {
        override fun supportsContext(context: Observation.Context): Boolean = true
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
