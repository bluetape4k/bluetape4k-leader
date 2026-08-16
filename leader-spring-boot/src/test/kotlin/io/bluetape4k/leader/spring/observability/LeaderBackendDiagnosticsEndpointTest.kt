package io.bluetape4k.leader.spring.observability

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsAware
import io.bluetape4k.leader.diagnostics.LeaderBackendConnectivity
import io.bluetape4k.leader.diagnostics.LeaderBackendConnectivityStatus
import io.bluetape4k.leader.diagnostics.LeaderBackendDescriptor
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider
import io.bluetape4k.leader.diagnostics.LocalLeaderBackendDiagnostics
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.getBean
import org.springframework.beans.factory.getBeansOfType
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration

class LeaderBackendDiagnosticsEndpointTest {

    private val runner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                LeaderElectionObservabilityAutoConfiguration::class.java,
                LeaderBackendDiagnosticsActuatorAutoConfiguration::class.java,
            )
        )
        .withUserConfiguration(DiagnosticsElectorConfig::class.java)

    @Test
    fun `diagnostics endpoint는 기본적으로 등록하지 않는다`() {
        runner.run { context ->
            context.getBeansOfType<LeaderBackendDiagnosticsEndpoint>().isEmpty().shouldBeTrue()
        }
    }

    @Test
    fun `Actuator API가 없으면 diagnostics endpoint auto configuration을 적용하지 않는다`() {
        runner
            .withClassLoader(FilteredClassLoader("org.springframework.boot.actuate.endpoint.annotation"))
            .withPropertyValues("management.endpoint.leaderBackendDiagnostics.enabled=true")
            .run { context ->
                context.containsBean("leaderBackendDiagnosticsEndpoint").shouldBeFalse()
            }
    }

    @Test
    fun `diagnostics endpoint는 probe 없이 정적 descriptor를 반환한다`() {
        runner
            .withPropertyValues("management.endpoint.leaderBackendDiagnostics.enabled=true")
            .run { context ->
                val elector = context.getBean<RecordingDiagnosticsElector>()

                val diagnostics = context.getBean<LeaderBackendDiagnosticsEndpoint>()
                    .leaderBackendDiagnostics()

                diagnostics.descriptor shouldBeEqualTo LocalLeaderBackendDiagnostics.backendDescriptor
                diagnostics.connectivity.status shouldBeEqualTo LeaderBackendConnectivityStatus.NOT_CHECKED
                elector.probeCalls.get() shouldBeEqualTo 0
            }
    }

    @Test
    fun `선택된 elector가 provider를 제공하지 않으면 endpoint를 등록하지 않는다`() {
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    LeaderElectionObservabilityAutoConfiguration::class.java,
                    LeaderBackendDiagnosticsActuatorAutoConfiguration::class.java,
                )
            )
            .withUserConfiguration(PlainElectorConfig::class.java)
            .withPropertyValues("management.endpoint.leaderBackendDiagnostics.enabled=true")
            .run { context ->
                context.getBeansOfType<LeaderBackendDiagnosticsEndpoint>().isEmpty().shouldBeTrue()
            }
    }

    @Test
    fun `diagnostics aware wrapper가 전달한 provider를 선택한다`() {
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    LeaderElectionObservabilityAutoConfiguration::class.java,
                    LeaderBackendDiagnosticsActuatorAutoConfiguration::class.java,
                )
            )
            .withUserConfiguration(DiagnosticsAwareElectorConfig::class.java)
            .withPropertyValues("management.endpoint.leaderBackendDiagnostics.enabled=true")
            .run { context ->
                val diagnostics = context.getBean<LeaderBackendDiagnosticsEndpoint>()
                    .leaderBackendDiagnostics()

                diagnostics.descriptor shouldBeEqualTo LocalLeaderBackendDiagnostics.backendDescriptor
                diagnostics.connectivity.status shouldBeEqualTo LeaderBackendConnectivityStatus.NOT_CHECKED
            }
    }

    @Test
    fun `diagnostics aware wrapper가 provider를 제공하지 않으면 endpoint 타입을 등록하지 않는다`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(LeaderBackendDiagnosticsActuatorAutoConfiguration::class.java))
            .withUserConfiguration(NullDiagnosticsAwareElectorConfig::class.java)
            .withPropertyValues("management.endpoint.leaderBackendDiagnostics.enabled=true")
            .run { context ->
                context.getBeansOfType<LeaderBackendDiagnosticsEndpoint>().isEmpty().shouldBeTrue()
            }
    }

    @Test
    fun `diagnostics auto configuration은 observability 다음 순서로 등록한다`() {
        val imports = javaClass.classLoader
            .getResource("META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")
            .shouldNotBeNull()
            .readText()
            .lines()

        val observabilityIndex = imports.indexOf(LeaderElectionObservabilityAutoConfiguration::class.qualifiedName)
        val diagnosticsIndex = imports.indexOf(LeaderBackendDiagnosticsActuatorAutoConfiguration::class.qualifiedName)
        val healthIndex = imports.indexOf(LeaderBackendHealthAutoConfiguration::class.qualifiedName)

        (observabilityIndex >= 0).shouldBeTrue()
        (diagnosticsIndex > observabilityIndex).shouldBeTrue()
        (healthIndex > observabilityIndex).shouldBeTrue()
    }

    @Configuration(proxyBeanMethods = false)
    class DiagnosticsElectorConfig {
        @Bean
        fun testLeaderElector(): RecordingDiagnosticsElector = RecordingDiagnosticsElector()
    }

    @Configuration(proxyBeanMethods = false)
    class PlainElectorConfig {
        @Bean
        fun testLeaderElector(): LeaderElector = PlainLeaderElector()
    }

    @Configuration(proxyBeanMethods = false)
    class DiagnosticsAwareElectorConfig {
        @Bean
        fun testLeaderElector(): LeaderElector = DiagnosticsAwareElector(RecordingDiagnosticsElector())
    }

    @Configuration(proxyBeanMethods = false)
    class NullDiagnosticsAwareElectorConfig {
        @Bean
        fun testLeaderElector(): LeaderElector = DiagnosticsAwareElector(null)
    }

    class RecordingDiagnosticsElector : PlainLeaderElector(), LeaderBackendDiagnosticsProvider {
        val probeCalls = AtomicInteger()

        override val backendDescriptor: LeaderBackendDescriptor = LocalLeaderBackendDiagnostics.backendDescriptor

        override fun checkConnectivity(timeout: Duration): LeaderBackendConnectivity {
            probeCalls.incrementAndGet()
            return LeaderBackendConnectivity.notChecked()
        }
    }

    class DiagnosticsAwareElector(
        override val backendDiagnosticsProvider: LeaderBackendDiagnosticsProvider?,
    ) : PlainLeaderElector(), LeaderBackendDiagnosticsAware

    open class PlainLeaderElector : LeaderElector {
        override fun <T> runIfLeader(lockName: String, action: () -> T): T? = action()

        override fun <T> runAsyncIfLeader(
            lockName: String,
            executor: Executor,
            action: () -> CompletableFuture<T>,
        ): CompletableFuture<T?> = action().thenApply { it }
    }
}
