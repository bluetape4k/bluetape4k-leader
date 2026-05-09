package io.bluetape4k.leader.micrometer

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.metrics.SkipReason
import io.bluetape4k.testcontainers.infra.PrometheusServer
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.MountableFile
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PrometheusExportTest {

    @Test
    fun `Prometheus registry scrape exports AOP and direct elector metrics`() {
        val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

        recordAopMetrics(registry, "aop-job")
        recordDirectMetrics(registry, "direct-job", "direct-skip-job")

        val scrape = registry.scrape()

        scrape shouldContain """leader_aop_attempts_total{lock_name="aop-job"} 1.0"""
        scrape shouldContain """leader_aop_acquired_total{lock_name="aop-job"} 1.0"""
        scrape shouldContain """leader_aop_lock_not_acquired_total{lock_name="aop-job",reason="CONTENTION"} 1.0"""
        scrape shouldContain """leader_aop_execution_duration_seconds_count{lock_name="aop-job"} 1"""
        scrape shouldContain """leader_aop_task_failed_total{exception="IllegalStateException",lock_name="aop-job"} 1.0"""
        scrape shouldContain """leader_aop_active{lock_name="aop-job"} 0.0"""

        scrape shouldContain """shedlock_leader_acquired_total{lock_name="direct-job"} 1.0"""
        scrape shouldContain """shedlock_leader_not_acquired_total{lock_name="direct-skip-job"} 1.0"""
        scrape shouldContain """shedlock_leader_duration_seconds_count{lock_name="direct-job"} 1"""
        scrape shouldContain """shedlock_leader_active{lock_name="direct-job"} 0.0"""
    }

    @Test
    fun `PrometheusServer scrapes leader metrics endpoint`(@TempDir tempDir: Path) {
        val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        recordAopMetrics(registry, "aop-container-job")
        recordDirectMetrics(registry, "direct-container-job", "direct-container-skip-job")

        val scrapeFile = writeScrapeFile(tempDir, registry.scrape())
        val prometheusConfig = writePrometheusConfig(tempDir)
        Network.newNetwork().use { network ->
            MetricsTargetContainer().apply {
                withNetwork(network)
                withNetworkAliases(METRICS_TARGET_ALIAS)
                withCopyFileToContainer(
                    MountableFile.forHostPath(scrapeFile),
                    "/srv/metrics/metrics.prom",
                )
                start()
            }.use {

                PrometheusServer(reuse = false).apply {
                    withNetwork(network)
                    withCopyFileToContainer(
                        MountableFile.forHostPath(prometheusConfig),
                        "/etc/prometheus/prometheus.yml",
                    )
                    start()
                }.use { prometheus ->
                    await.atMost(45.seconds.toJavaDuration())
                        .pollInterval(1.seconds.toJavaDuration())
                        .untilAsserted {
                            val upResponse = queryPrometheus(
                                prometheus.url,
                                """up{job="leader-micrometer"}""",
                            )
                            upResponse shouldContain """"status":"success""""
                            upResponse shouldContain """"job":"leader-micrometer""""
                            upResponse shouldContain """"1""""

                            val aopResponse = queryPrometheus(
                                prometheus.url,
                                """leader_aop_acquired_total{lock_name="aop-container-job"}""",
                            )
                            aopResponse shouldContain """"status":"success""""
                            aopResponse shouldContain """"lock_name":"aop-container-job""""

                            val aopFailureResponse = queryPrometheus(
                                prometheus.url,
                                """leader_aop_task_failed_total{lock_name="aop-container-job"}""",
                            )
                            aopFailureResponse shouldContain """"status":"success""""
                            aopFailureResponse shouldContain """"exception":"IllegalStateException""""

                            val directResponse = queryPrometheus(
                                prometheus.url,
                                """shedlock_leader_acquired_total{lock_name="direct-container-job"}""",
                            )
                            directResponse shouldContain """"status":"success""""
                            directResponse shouldContain """"lock_name":"direct-container-job""""
                        }
                }
            }
        }
    }

    private fun recordAopMetrics(registry: PrometheusMeterRegistry, lockName: String) {
        val recorder = MicrometerLeaderAopMetricsRecorder(registry)
        val options = LeaderElectionOptions.Default

        recorder.onLockAttempt(lockName, options)
        recorder.onLockAcquired(lockName, options, 5.milliseconds)
        recorder.onTaskStarted(lockName)
        recorder.onTaskFinished(lockName, 25.milliseconds)
        recorder.onTaskFailed(lockName, 1.milliseconds, IllegalStateException("boom"))
        recorder.onLockNotAcquired(lockName, options, SkipReason.CONTENTION)
    }

    private fun recordDirectMetrics(
        registry: PrometheusMeterRegistry,
        acquiredLockName: String,
        skippedLockName: String,
    ) {
        InstrumentedLeaderElector(StubLeaderElector(elected = true), registry)
            .runIfLeader(acquiredLockName) { "done" } shouldBeEqualTo "done"

        InstrumentedLeaderElector(StubLeaderElector(elected = false), registry)
            .runIfLeader(skippedLockName) { "not-called" } shouldBeEqualTo null
    }

    private fun writeScrapeFile(tempDir: Path, scrape: String): Path {
        val metricsDir = tempDir.resolve("metrics")
        Files.createDirectories(metricsDir)
        return metricsDir.resolve("metrics.prom")
            .also { Files.writeString(it, scrape) }
    }

    private fun writePrometheusConfig(tempDir: Path): Path {
        val config = tempDir.resolve("prometheus.yml")
        Files.writeString(
            config,
            """
            global:
              scrape_interval: 1s
              evaluation_interval: 1s
            scrape_configs:
              - job_name: "leader-micrometer"
                metrics_path: "/metrics"
                static_configs:
                  - targets: ["$METRICS_TARGET_ALIAS:$METRICS_TARGET_PORT"]
            """.trimIndent(),
        )
        return config
    }

    private fun queryPrometheus(prometheusUrl: String, query: String): String {
        val encodedQuery = URLEncoder.encode(query, UTF_8)
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$prometheusUrl/api/v1/query?query=$encodedQuery"))
            .GET()
            .build()

        return HttpClient.newHttpClient()
            .send(request, HttpResponse.BodyHandlers.ofString())
            .body()
    }

    private class StubLeaderElector(
        private val elected: Boolean,
    ) : LeaderElector {

        override fun <T> runIfLeader(lockName: String, action: () -> T): T? =
            if (elected) action() else null

        override fun <T> runAsyncIfLeader(
            lockName: String,
            executor: Executor,
            action: () -> CompletableFuture<T>,
        ): CompletableFuture<T?> =
            CompletableFuture.completedFuture(if (elected) action().join() else null)
    }

    private class MetricsTargetContainer :
        GenericContainer<MetricsTargetContainer>(DockerImageName.parse("python:3.13-alpine")) {

        init {
            withExposedPorts(METRICS_TARGET_PORT)
            withCommand(
                "python",
                "-c",
                """
                from http.server import BaseHTTPRequestHandler, HTTPServer
                from pathlib import Path

                data = Path("/srv/metrics/metrics.prom").read_bytes()

                class Handler(BaseHTTPRequestHandler):
                    def do_GET(self):
                        if self.path != "/metrics":
                            self.send_response(404)
                            self.end_headers()
                            return

                        self.send_response(200)
                        self.send_header("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
                        self.send_header("Content-Length", str(len(data)))
                        self.end_headers()
                        self.wfile.write(data)

                HTTPServer(("0.0.0.0", $METRICS_TARGET_PORT), Handler).serve_forever()
                """.trimIndent(),
            )
            waitingFor(Wait.forHttp("/metrics").forPort(METRICS_TARGET_PORT))
        }
    }

    private companion object {
        const val METRICS_TARGET_ALIAS = "leader-metrics-target"
        const val METRICS_TARGET_PORT = 8000
    }
}
