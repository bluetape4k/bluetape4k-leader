package io.bluetape4k.leader.examples.prometheus

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.diagnostics.LeaderBackendConnectivity
import io.bluetape4k.leader.diagnostics.LeaderBackendConnectivityReason
import io.bluetape4k.leader.diagnostics.LeaderBackendConnectivityStatus
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider
import io.bluetape4k.leader.diagnostics.LocalLeaderBackendDiagnostics
import io.bluetape4k.leader.micrometer.InstrumentedLeaderElector
import io.bluetape4k.leader.micrometer.LeaderMetricTagOptions
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PrometheusBackendConnectivityProbeTest {

    @Test
    fun `probe records UP, DOWN, and UNKNOWN connectivity states`() {
        listOf(
            LeaderBackendConnectivity.up(
                checkedAt = Instant.EPOCH,
                reason = LeaderBackendConnectivityReason.CONNECTED,
            ),
            LeaderBackendConnectivity.down(
                checkedAt = Instant.EPOCH,
                reason = LeaderBackendConnectivityReason.DISCONNECTED,
            ),
            LeaderBackendConnectivity.unknown(
                checkedAt = Instant.EPOCH,
                reason = LeaderBackendConnectivityReason.CLIENT_STATE_UNCONFIRMED,
            ),
        ).forEach { expected ->
            val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
            val provider = FixedDiagnosticsProvider(expected)
            val probe = PrometheusBackendConnectivityProbe(
                provider = instrumentedProvider(provider, registry),
                timeoutMillis = 500,
            )

            probe.probe()
            probe.probe()

            provider.observedTimeout shouldBeEqualTo 500.milliseconds
            provider.probeCount shouldBeEqualTo 2
            registry.connectivityCount(expected.status, expected.reason) shouldBeEqualTo 2.0
            registry.scrape().hasConnectivitySeries(expected.status, expected.reason).shouldBeTrue()
        }
    }

    @Test
    fun `probe records provider exceptions as UNKNOWN without replacing the failure`() {
        val failure = IllegalStateException("redis probe failed")
        val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        val probe = PrometheusBackendConnectivityProbe(
            provider = instrumentedProvider(FixedDiagnosticsProvider(failure = failure), registry),
            timeoutMillis = 500,
        )

        val thrown = assertFailsWith<IllegalStateException> {
            probe.probe()
        }

        thrown shouldBeSameInstanceAs failure
        registry.connectivityCount(
            LeaderBackendConnectivityStatus.UNKNOWN,
            LeaderBackendConnectivityReason.PROVIDER_EXCEPTION,
        ) shouldBeEqualTo 1.0
        registry.scrape()
            .hasConnectivitySeries(
                LeaderBackendConnectivityStatus.UNKNOWN,
                LeaderBackendConnectivityReason.PROVIDER_EXCEPTION,
            ).shouldBeTrue()
    }

    @Test
    fun `probe rejects a non-positive timeout`() {
        val provider = FixedDiagnosticsProvider(LeaderBackendConnectivity.unknown(Instant.EPOCH))

        assertFailsWith<IllegalArgumentException> {
            PrometheusBackendConnectivityProbe(provider, timeoutMillis = 0)
        }
    }

    @Test
    fun `passive diagnostics do not create connectivity series`() {
        val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        val provider = instrumentedProvider(
            FixedDiagnosticsProvider(
                connectivity = LeaderBackendConnectivity.unknown(Instant.EPOCH),
            ),
            registry,
        )

        provider.diagnostics(probe = false)

        registry.scrape().contains("leader_backend_connectivity_total").shouldBeFalse()
    }

    private fun instrumentedProvider(
        provider: LeaderBackendDiagnosticsProvider,
        registry: PrometheusMeterRegistry,
    ): LeaderBackendDiagnosticsProvider {
        val delegate = object :
            LeaderElector by StubLeaderElector,
            LeaderBackendDiagnosticsProvider by provider {}
        return requireNotNull(InstrumentedLeaderElector(delegate, registry).backendDiagnosticsProvider)
    }

    private fun PrometheusMeterRegistry.connectivityCount(
        status: LeaderBackendConnectivityStatus,
        reason: LeaderBackendConnectivityReason,
    ): Double =
        find(BACKEND_CONNECTIVITY_METER)
            .tag(LeaderMetricTagOptions.TAG_BACKEND_NAME, "redis-lettuce")
            .tag("status", status.name)
            .tag("reason", reason.name)
            .counter()
            .shouldNotBeNull()
            .count()

    private fun String.hasConnectivitySeries(
        status: LeaderBackendConnectivityStatus,
        reason: LeaderBackendConnectivityReason,
    ): Boolean {
        val labels = listOf(
            "backend_name=\"redis-lettuce\"",
            "status=\"" + status.name + "\"",
            "reason=\"" + reason.name + "\"",
        ).joinToString(separator = "") { "(?=[^}]*${Regex.escape(it)})" }
        return Regex("""leader_backend_connectivity_total\{${labels}[^}]*}\s+[0-9.Ee+-]+""")
            .containsMatchIn(this)
    }

    private class FixedDiagnosticsProvider(
        private val connectivity: LeaderBackendConnectivity? = null,
        private val failure: RuntimeException? = null,
    ) : LeaderBackendDiagnosticsProvider {

        var observedTimeout: Duration? = null
        var probeCount: Int = 0

        override val backendDescriptor =
            LocalLeaderBackendDiagnostics.backendDescriptor.copy(backendId = "redis-lettuce")

        override fun checkConnectivity(timeout: Duration): LeaderBackendConnectivity {
            observedTimeout = timeout
            probeCount++
            failure?.let { throw it }
            return requireNotNull(connectivity)
        }
    }

    private object StubLeaderElector : LeaderElector {
        override fun <T> runIfLeader(lockName: String, action: () -> T): T? = null

        override fun <T> runAsyncIfLeader(
            lockName: String,
            executor: Executor,
            action: () -> CompletableFuture<T>,
        ): CompletableFuture<T?> = CompletableFuture.completedFuture(null)
    }

    private companion object {
        const val BACKEND_CONNECTIVITY_METER = "leader.backend.connectivity"
    }
}
