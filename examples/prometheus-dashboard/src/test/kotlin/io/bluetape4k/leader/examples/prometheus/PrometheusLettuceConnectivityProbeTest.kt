package io.bluetape4k.leader.examples.prometheus

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.diagnostics.LeaderBackendConnectivityReason
import io.bluetape4k.leader.diagnostics.LeaderBackendConnectivityStatus
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider
import io.bluetape4k.leader.lettuce.LettuceLeaderBackendDiagnostics
import io.bluetape4k.leader.micrometer.InstrumentedLeaderElector
import io.bluetape4k.leader.micrometer.LeaderMetricTagOptions
import io.bluetape4k.testcontainers.storage.RedisServer
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.codec.StringCodec
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class PrometheusLettuceConnectivityProbeTest {

    @Test
    fun `closed real Lettuce connection exports DOWN through the example probe`() {
        val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        val connection = RedisServer.Launcher.LettuceLib.getRedisClient()
            .connect(StringCodec.UTF8)

        try {
            val provider = instrumentedProvider(LettuceLeaderBackendDiagnostics(connection), registry)
            val probe = PrometheusBackendConnectivityProbe(provider, timeoutMillis = 500)

            connection.close()
            await.atMost(Duration.ofSeconds(5)).untilAsserted {
                connection.isOpen.shouldBeFalse()
            }
            val connectivity = LettuceLeaderBackendDiagnostics(connection)
                .checkConnectivity(500.milliseconds)
            connectivity.status shouldBeEqualTo LeaderBackendConnectivityStatus.DOWN
            connectivity.reason shouldBeEqualTo LeaderBackendConnectivityReason.DISCONNECTED
            probe.probe()

            registry.connectivityCount(
                LeaderBackendConnectivityStatus.DOWN,
                LeaderBackendConnectivityReason.DISCONNECTED,
            ) shouldBeEqualTo 1.0
            registry.scrape()
                .hasConnectivitySeries(
                    LeaderBackendConnectivityStatus.DOWN,
                    LeaderBackendConnectivityReason.DISCONNECTED,
                ).shouldBeTrue()
        } finally {
            connection.close()
        }
    }

    @Test
    fun `real Lettuce connection provider exception exports UNKNOWN`() {
        val failure = IllegalStateException("redis connectivity probe failed")
        val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        val connection = RedisServer.Launcher.LettuceLib.getRedisClient()
            .connect(StringCodec.UTF8)

        try {
            val failingConnection = connection.withIsOpenFailure(failure)
            val diagnostics = LettuceLeaderBackendDiagnostics(failingConnection)
            val connectivity = diagnostics.checkConnectivity(500.milliseconds)
            connectivity.status shouldBeEqualTo LeaderBackendConnectivityStatus.UNKNOWN
            connectivity.reason shouldBeEqualTo LeaderBackendConnectivityReason.PROVIDER_EXCEPTION

            val provider = instrumentedProvider(diagnostics, registry)
            val probe = PrometheusBackendConnectivityProbe(provider, timeoutMillis = 500)

            probe.probe()
            registry.connectivityCount(
                LeaderBackendConnectivityStatus.UNKNOWN,
                LeaderBackendConnectivityReason.PROVIDER_EXCEPTION,
            ) shouldBeEqualTo 1.0
            registry.scrape()
                .hasConnectivitySeries(
                    LeaderBackendConnectivityStatus.UNKNOWN,
                    LeaderBackendConnectivityReason.PROVIDER_EXCEPTION,
                ).shouldBeTrue()
        } finally {
            connection.close()
        }
    }

    private fun instrumentedProvider(
        provider: LeaderBackendDiagnosticsProvider,
        registry: PrometheusMeterRegistry,
    ): LeaderBackendDiagnosticsProvider {
        val delegate = object :
            LeaderElector by StubLeaderElector,
            LeaderBackendDiagnosticsProvider by provider {}
        return requireNotNull(
            InstrumentedLeaderElector(
                delegate = delegate,
                registry = registry,
                tagOptions = LeaderMetricTagOptions.Default,
            ).backendDiagnosticsProvider,
        )
    }

    private fun PrometheusMeterRegistry.connectivityCount(
        status: LeaderBackendConnectivityStatus,
        reason: LeaderBackendConnectivityReason,
    ): Double =
        find("leader.backend.connectivity")
            .tag(LeaderMetricTagOptions.TAG_BACKEND_NAME, "redis-lettuce")
            .tag("status", status.name)
            .tag("reason", reason.name)
            .counter()
            ?.count()
            ?: error("connectivity meter is missing for $status/$reason")

    private fun String.hasConnectivitySeries(
        status: LeaderBackendConnectivityStatus,
        reason: LeaderBackendConnectivityReason,
    ): Boolean {
        val labels = listOf(
            "backend_name=\"redis-lettuce\"",
            "status=\"" + status.name + "\"",
            "reason=\"" + reason.name + "\"",
        ).joinToString(separator = "") { "(?=[^}]*${Regex.escape(it)})" }
        return Regex("""leader_backend_connectivity_total\{${labels}[^}]*}\s+([1-9][0-9.Ee+-]*)""")
            .containsMatchIn(this)
    }

    @Suppress("UNCHECKED_CAST")
    private fun StatefulRedisConnection<String, String>.withIsOpenFailure(
        failure: RuntimeException,
    ): StatefulRedisConnection<String, String> {
        val handler = InvocationHandler { _: Any, method: Method, args: Array<out Any?>? ->
            if (method.name == "isOpen" && method.parameterCount == 0) {
                throw failure
            }
            method.invoke(this, *(args ?: emptyArray()))
        }
        return Proxy.newProxyInstance(
            StatefulRedisConnection::class.java.classLoader,
            arrayOf(StatefulRedisConnection::class.java),
            handler,
        ) as StatefulRedisConnection<String, String>
    }

    private object StubLeaderElector : LeaderElector {
        override fun <T> runIfLeader(lockName: String, action: () -> T): T? = null

        override fun <T> runAsyncIfLeader(
            lockName: String,
            executor: Executor,
            action: () -> CompletableFuture<T>,
        ): CompletableFuture<T?> = CompletableFuture.completedFuture(null)
    }
}
