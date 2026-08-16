package io.bluetape4k.leader.spring.observability

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
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
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.boot.health.contributor.Status
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class LeaderBackendHealthIndicatorTest {

    @Test
    fun `backend health는 기본적으로 등록하지 않는다`() {
        runner().run { context ->
            context.getBeansOfType<LeaderBackendHealthIndicator>().isEmpty().shouldBeTrue()
        }
    }

    @Test
    fun `Spring health API가 없으면 backend health auto configuration을 적용하지 않는다`() {
        runner()
            .withClassLoader(FilteredClassLoader("org.springframework.boot.health.contributor"))
            .withPropertyValues("bluetape4k.leader.observability.backend-health.enabled=true")
            .run { context ->
                context.containsBean("leaderBackendHealthIndicator").shouldBeFalse()
            }
    }

    @Test
    fun `backend health는 설정한 timeout으로 선택된 provider를 한 번 호출한다`() {
        runner()
            .withPropertyValues(
                "bluetape4k.leader.observability.backend-health.enabled=true",
                "bluetape4k.leader.observability.backend-health.timeout=275ms",
            )
            .run { context ->
                val elector = context.getBean<RecordingDiagnosticsElector>()

                val health = context.getBean<LeaderBackendHealthIndicator>().health()

                health.status shouldBeEqualTo Status.UP
                elector.probeCalls.get() shouldBeEqualTo 1
                elector.lastTimeout shouldBeEqualTo 275.milliseconds
            }
    }

    @Test
    fun `선택된 elector가 provider를 제공하지 않으면 backend health를 등록하지 않는다`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(LeaderBackendHealthAutoConfiguration::class.java))
            .withUserConfiguration(PlainElectorConfig::class.java)
            .withPropertyValues("bluetape4k.leader.observability.backend-health.enabled=true")
            .run { context ->
                context.getBeansOfType<HealthIndicator>().isEmpty().shouldBeTrue()
            }
    }

    @Test
    fun `diagnostics aware wrapper가 provider를 제공하지 않으면 backend health 타입을 등록하지 않는다`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(LeaderBackendHealthAutoConfiguration::class.java))
            .withUserConfiguration(NullDiagnosticsAwareElectorConfig::class.java)
            .withPropertyValues("bluetape4k.leader.observability.backend-health.enabled=true")
            .run { context ->
                context.getBeansOfType<HealthIndicator>().isEmpty().shouldBeTrue()
            }
    }

    @Test
    fun `connectivity 상태를 Spring health 상태로 변환한다`() {
        val mappings = mapOf(
            LeaderBackendConnectivityStatus.UP to Status.UP,
            LeaderBackendConnectivityStatus.DOWN to Status.DOWN,
            LeaderBackendConnectivityStatus.UNKNOWN to Status.UNKNOWN,
            LeaderBackendConnectivityStatus.NOT_CHECKED to Status.UNKNOWN,
        )

        mappings.forEach { (connectivityStatus, expectedHealthStatus) ->
            val provider = RecordingDiagnosticsElector(connectivityStatus)

            val health = LeaderBackendHealthIndicator(provider, 100.milliseconds).health()

            health.status shouldBeEqualTo expectedHealthStatus
            provider.probeCalls.get() shouldBeEqualTo 1
        }
    }

    private fun runner(): ApplicationContextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(LeaderBackendHealthAutoConfiguration::class.java))
        .withUserConfiguration(DiagnosticsElectorConfig::class.java)

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
    class NullDiagnosticsAwareElectorConfig {
        @Bean
        fun testLeaderElector(): LeaderElector = DiagnosticsAwareElector(null)
    }

    class DiagnosticsAwareElector(
        override val backendDiagnosticsProvider: LeaderBackendDiagnosticsProvider?,
    ) : PlainLeaderElector(), LeaderBackendDiagnosticsAware

    class RecordingDiagnosticsElector(
        private val connectivityStatus: LeaderBackendConnectivityStatus = LeaderBackendConnectivityStatus.UP,
    ) : PlainLeaderElector(), LeaderBackendDiagnosticsProvider {
        val probeCalls = AtomicInteger()
        var lastTimeout: Duration? = null

        override val backendDescriptor: LeaderBackendDescriptor = LocalLeaderBackendDiagnostics.backendDescriptor

        override fun checkConnectivity(timeout: Duration): LeaderBackendConnectivity {
            probeCalls.incrementAndGet()
            lastTimeout = timeout
            return when (connectivityStatus) {
                LeaderBackendConnectivityStatus.UP -> LeaderBackendConnectivity.up(Instant.EPOCH)
                LeaderBackendConnectivityStatus.DOWN -> LeaderBackendConnectivity.down(Instant.EPOCH)
                LeaderBackendConnectivityStatus.UNKNOWN -> LeaderBackendConnectivity.unknown(Instant.EPOCH)
                LeaderBackendConnectivityStatus.NOT_CHECKED -> LeaderBackendConnectivity.notChecked()
            }
        }
    }

    open class PlainLeaderElector : LeaderElector {
        override fun <T> runIfLeader(lockName: String, action: () -> T): T? = action()

        override fun <T> runAsyncIfLeader(
            lockName: String,
            executor: Executor,
            action: () -> CompletableFuture<T>,
        ): CompletableFuture<T?> = action().thenApply { it }
    }
}
