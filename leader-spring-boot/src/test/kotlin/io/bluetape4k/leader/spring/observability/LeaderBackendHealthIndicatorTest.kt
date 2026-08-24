package io.bluetape4k.leader.spring.observability

import com.fasterxml.jackson.databind.ObjectMapper
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsAware
import io.bluetape4k.leader.diagnostics.LeaderBackendConnectivity
import io.bluetape4k.leader.diagnostics.LeaderBackendConnectivityStatus
import io.bluetape4k.leader.diagnostics.LeaderBackendDescriptor
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnostics
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProbe
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider
import io.bluetape4k.leader.diagnostics.LocalLeaderBackendDiagnostics
import io.bluetape4k.leader.spring.LeaderTestApplication
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.beans.factory.getBean
import org.springframework.beans.factory.getBeansOfType
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.boot.health.contributor.Status
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

@ExtendWith(OutputCaptureExtension::class)
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

    @Test
    fun `provider Exception은 UNKNOWN으로 정규화하고 원문 health detail을 노출하지 않는다`() {
        val rawMessage = RAW_PROBE_MESSAGE
        val health = LeaderBackendHealthIndicator(
            ThrowingDiagnosticsElector(IllegalStateException(rawMessage)),
            100.milliseconds,
        ).health()

        health.status shouldBeEqualTo Status.UNKNOWN
        health.details.keys.none { it in SENSITIVE_DETAIL_KEYS }.shouldBeTrue()
        health.details.values.none { it.toString().contains(rawMessage) }.shouldBeTrue()
    }

    @Test
    fun `공통 probe의 일반 Exception은 warning 없이 UNKNOWN으로 정규화된다`(output: CapturedOutput) {
        val health = LeaderBackendHealthIndicator(
            ProbeBackedDiagnosticsElector { throw IllegalStateException(RAW_PROBE_MESSAGE) },
            100.milliseconds,
        ).health()

        health.status shouldBeEqualTo Status.UNKNOWN
        output.out.contains(WARNING_MESSAGE).shouldBeFalse()
        output.out.contains(RAW_PROBE_MESSAGE).shouldBeFalse()
    }

    @Test
    fun `provider CancellationException도 UNKNOWN으로 정규화하고 원문을 노출하지 않는다`() {
        val health = LeaderBackendHealthIndicator(
            ThrowingDiagnosticsElector(CancellationException(RAW_PROBE_MESSAGE)),
            100.milliseconds,
        ).health()

        health.status shouldBeEqualTo Status.UNKNOWN
        health.details.keys.none { it in SENSITIVE_DETAIL_KEYS }.shouldBeTrue()
        health.details.values.none { it.toString().contains(RAW_PROBE_MESSAGE) }.shouldBeTrue()
    }

    @Test
    fun `공통 probe의 CancellationException도 UNKNOWN warning으로 정규화된다`(output: CapturedOutput) {
        val health = LeaderBackendHealthIndicator(
            ProbeBackedDiagnosticsElector { throw CancellationException(RAW_PROBE_MESSAGE) },
            100.milliseconds,
        ).health()

        health.status shouldBeEqualTo Status.UNKNOWN
        output.out shouldContain WARNING_MESSAGE
        output.out.contains(RAW_PROBE_MESSAGE).shouldBeFalse()
    }

    @Test
    fun `provider InterruptedException은 UNKNOWN으로 정규화하고 interrupt flag를 복원한다`() {
        Thread.interrupted()
        try {
            val health = LeaderBackendHealthIndicator(
                ThrowingDiagnosticsElector(InterruptedException(RAW_PROBE_MESSAGE)),
                100.milliseconds,
            ).health()

            health.status shouldBeEqualTo Status.UNKNOWN
            health.details.keys.none { it in SENSITIVE_DETAIL_KEYS }.shouldBeTrue()
            health.details.values.none { it.toString().contains(RAW_PROBE_MESSAGE) }.shouldBeTrue()
            Thread.currentThread().isInterrupted.shouldBeTrue()
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `provider Error는 재전파한다`() {
        val fatal = AssertionError("fatal backend probe")

        val thrown = assertFailsWith<AssertionError> {
            LeaderBackendHealthIndicator(ThrowingDiagnosticsElector(fatal), 100.milliseconds).health()
        }

        thrown shouldBeSameInstanceAs fatal
    }

    @Test
    fun `공통 probe의 NOT_CHECKED callback은 UNKNOWN warning으로 정규화된다`(output: CapturedOutput) {
        val health = LeaderBackendHealthIndicator(
            ProbeBackedDiagnosticsElector { LeaderBackendConnectivityStatus.NOT_CHECKED },
            100.milliseconds,
        ).health()

        health.status shouldBeEqualTo Status.UNKNOWN
        output.out shouldContain WARNING_MESSAGE
    }

    @Test
    fun `사용자 정의 diagnostics의 NOT_CHECKED 결과는 warning 없이 UNKNOWN이다`(output: CapturedOutput) {
        val health = LeaderBackendHealthIndicator(
            NotCheckedDiagnosticsElector(),
            100.milliseconds,
        ).health()

        health.status shouldBeEqualTo Status.UNKNOWN
        output.out.contains(WARNING_MESSAGE).shouldBeFalse()
    }

    @Test
    fun `공통 probe의 Error는 동일 인스턴스로 재전파하고 warning을 남기지 않는다`(output: CapturedOutput) {
        val fatal = AssertionError(RAW_PROBE_MESSAGE)

        val thrown = assertFailsWith<AssertionError> {
            LeaderBackendHealthIndicator(
                ProbeBackedDiagnosticsElector { throw fatal },
                100.milliseconds,
            ).health()
        }

        thrown shouldBeSameInstanceAs fatal
        output.out.contains(WARNING_MESSAGE).shouldBeFalse()
    }

    @Nested
    @SpringBootTest(
        classes = [LeaderTestApplication::class, ThrowingActuatorElectorConfig::class],
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = [
            "bluetape4k.leader.observability.backend-health.enabled=true",
            "management.endpoint.health.show-details=always",
            "management.endpoints.web.exposure.include=health",
        ],
    )
    inner class ActuatorHealthDetailsTest {
        @LocalServerPort
        private var port: Int = 0

        @Test
        fun `show-details가 always여도 provider 원문을 health 응답에 노출하지 않는다`() {
            val request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$port/actuator/health"))
                .GET()
                .build()
            val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())

            response.statusCode() shouldBeEqualTo 200
            response.body().shouldContain("\"status\":\"UNKNOWN\"")
            val body = response.body()
            SENSITIVE_DETAIL_KEYS.forEach { key ->
                body.contains("\"$key\"").shouldBeFalse()
            }
            SENSITIVE_RAW_VALUES.forEach { value ->
                body.contains(value).shouldBeFalse()
            }

            val leaderBackend = ObjectMapper().readTree(body).path("components").path("leaderBackend")
            leaderBackend.isObject.shouldBeTrue()
            leaderBackend.path("status").asText() shouldBeEqualTo "UNKNOWN"
            val leaderBackendFields = mutableSetOf<String>()
            val fields = leaderBackend.fieldNames()
            while (fields.hasNext()) {
                leaderBackendFields += fields.next()
            }
            leaderBackendFields shouldBeEqualTo setOf("status")
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

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    class ThrowingActuatorElectorConfig {
        @Bean
        fun testLeaderElector(): ThrowingDiagnosticsElector =
            ThrowingDiagnosticsElector(IllegalStateException(RAW_PROBE_MESSAGE))
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

    class ThrowingDiagnosticsElector(
        private val failure: Throwable,
    ) : PlainLeaderElector(), LeaderBackendDiagnosticsProvider {
        override val backendDescriptor: LeaderBackendDescriptor = LocalLeaderBackendDiagnostics.backendDescriptor

        override fun checkConnectivity(timeout: Duration): LeaderBackendConnectivity = throw failure
    }

    class ProbeBackedDiagnosticsElector(
        private val probe: (Duration) -> LeaderBackendConnectivityStatus,
    ) : PlainLeaderElector(), LeaderBackendDiagnosticsProvider {
        override val backendDescriptor: LeaderBackendDescriptor = LocalLeaderBackendDiagnostics.backendDescriptor

        override fun checkConnectivity(timeout: Duration): LeaderBackendConnectivity =
            LeaderBackendDiagnosticsProbe.check(
                timeout = timeout,
                clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                probe = probe,
            )
    }

    class NotCheckedDiagnosticsElector : PlainLeaderElector(), LeaderBackendDiagnosticsProvider {
        override val backendDescriptor: LeaderBackendDescriptor = LocalLeaderBackendDiagnostics.backendDescriptor

        override fun diagnostics(probe: Boolean, timeout: Duration): LeaderBackendDiagnostics =
            LeaderBackendDiagnostics(backendDescriptor, LeaderBackendConnectivity.notChecked())
    }

    open class PlainLeaderElector : LeaderElector {
        override fun <T> runIfLeader(lockName: String, action: () -> T): T? = action()

        override fun <T> runAsyncIfLeader(
            lockName: String,
            executor: Executor,
            action: () -> CompletableFuture<T>,
        ): CompletableFuture<T?> = action().thenApply { it }
    }

    private companion object {
        const val RAW_PROBE_MESSAGE =
            "probe failed endpoint=https://redis-prod.example:6380 token=secret credential=credential cause=connection"

        const val WARNING_MESSAGE =
            "leader.spring.health backend probe failed; status=UNKNOWN"

        val SENSITIVE_DETAIL_KEYS = setOf(
            "error",
            "exception",
            "exceptionClass",
            "class",
            "message",
            "cause",
            "stackTrace",
            "endpoint",
            "token",
            "credential",
        )

        val SENSITIVE_RAW_VALUES = setOf(
            RAW_PROBE_MESSAGE,
            "IllegalStateException",
            "redis-prod.example",
            "token=secret",
            "credential=credential",
            "cause=connection",
        )
    }
}
