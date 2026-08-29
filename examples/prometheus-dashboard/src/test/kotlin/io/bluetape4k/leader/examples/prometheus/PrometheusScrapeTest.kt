package io.bluetape4k.leader.examples.prometheus

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.leader.micrometer.LeaderMetricTagOptions
import io.bluetape4k.testcontainers.storage.RedisServer
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.awaitility.kotlin.withAlias
import org.awaitility.kotlin.withPollInterval
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.aop.support.AopUtils
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.time.Duration

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        // 아래에서 두 예약 컴포넌트를 명시적으로 실행해 scrape readiness가 scheduler 시작 순서에 의존하지 않게 한다.
        "demo.job.fixed-delay-ms=60000",
        "demo.job.initial-delay-ms=60000",
        "demo.backend-probe.fixed-delay-ms=60000",
        "demo.backend-probe.initial-delay-ms=60000",
        "demo.backend-probe.timeout-ms=500",
    ],
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PrometheusScrapeTest {

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var leaderScheduledJob: LeaderScheduledJob

    @Autowired
    private lateinit var backendConnectivityProbe: PrometheusBackendConnectivityProbe

    @Test
    fun `actuator prometheus exposes leader AOP metrics`() {
        AopUtils.isAopProxy(leaderScheduledJob) shouldBeEqualTo true
        leaderScheduledJob.dispatchBatch()
        backendConnectivityProbe.probe()
        backendConnectivityProbe.probe()

        await
            .withAlias("Prometheus endpoint and explicitly triggered leader metrics readiness")
            .atMost(Duration.ofSeconds(30))
            .withPollInterval(Duration.ofMillis(100))
            .untilAsserted {
                val scrape = scrapePrometheus().requireSuccessful()
                leaderScheduledJob.executionCount() shouldBeGreaterThan 0L
                scrape.assertMetricContract()
            }
    }

    private fun scrapePrometheus(): PrometheusScrapeResponse {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:$port/actuator/prometheus"))
            .timeout(Duration.ofSeconds(5))
            .GET()
            .build()

        return try {
            val response = httpClient.send(request, java.net.http.HttpResponse.BodyHandlers.ofString())
            PrometheusScrapeResponse(
                statusCode = response.statusCode(),
                body = response.body(),
            )
        } catch (e: IOException) {
            throw AssertionError("Prometheus scrape request failed: ${e.message}", e)
        }
    }

    private fun String.assertMetricContract() {
        requireMetrics(
            listOf(
                "leader_aop_attempts_total",
                "leader_aop_acquired_total",
                "leader_aop_active",
                "leader_history_sink_failures_total{sink=\"NoopLeaderHistorySink\"}",
                "leader_history_acquire_missing_total{sink=\"NoopLeaderHistorySink\"}",
                "leader_backend_connectivity_total",
            ),
        )
        requireLockNameSeries("leader_aop_attempts_total")
        requireLockNameSeries("leader_aop_acquired_total")
        requireLockNameSeries("leader_aop_active")
        this shouldNotContain """lock_name="${LeaderScheduledJob.LOCK_NAME}""""
        this shouldContain """leader_history_sink_failures_total{sink="NoopLeaderHistorySink"}"""
        this shouldContain """leader_history_acquire_missing_total{sink="NoopLeaderHistorySink"}"""
        requireConnectivitySeries(
            backendName = "redis-lettuce",
            status = "UNKNOWN",
            reason = "CLIENT_STATE_UNCONFIRMED",
        )
        connectivitySampleValue(
            backendName = "redis-lettuce",
            status = "UNKNOWN",
            reason = "CLIENT_STATE_UNCONFIRMED",
        ) shouldBeGreaterThan 1.0

        sampleValue("leader_aop_attempts_total") shouldBeGreaterThan 0.0
        sampleValue("leader_aop_acquired_total") shouldBeGreaterThan 0.0
    }

    private fun String.requireLockNameSeries(metricName: String) {
        val lockNameTag = Regex.escape("""lock_name="$EXPORTED_LOCK_NAME"""")
        if (!Regex("""$metricName\{[^}]*$lockNameTag[^}]*}\s+[0-9.Ee+-]+""").containsMatchIn(this)) {
            throw AssertionError(
                "Prometheus scrape is missing $metricName for lock=$EXPORTED_LOCK_NAME\nbody=$this",
            )
        }
    }

    private fun String.sampleValue(metricName: String): Double {
        val regex = Regex("""$metricName\{[^}]*lock_name="$EXPORTED_LOCK_NAME"[^}]*}\s+([0-9.Ee+-]+)""")
        return requireNotNull(regex.find(this)) {
            "$metricName for $EXPORTED_LOCK_NAME not found in scrape"
        }.groupValues[1].toDouble()
    }

    private fun String.requireConnectivitySeries(
        backendName: String,
        status: String,
        reason: String,
    ) {
        val labels = listOf(
            """backend_name="$backendName"""",
            """status="$status"""",
            """reason="$reason"""",
        ).joinToString(separator = "") { "(?=[^}]*${Regex.escape(it)})" }
        if (!Regex("""leader_backend_connectivity_total\{${labels}[^}]*}\s+[0-9.Ee+-]+""")
                .containsMatchIn(this)
        ) {
            throw AssertionError(
                "Prometheus scrape is missing connectivity series for $backendName/$status/$reason\nbody=$this",
            )
        }
    }

    private fun String.connectivitySampleValue(
        backendName: String,
        status: String,
        reason: String,
    ): Double {
        val labels = listOf(
            "backend_name=\"$backendName\"",
            "status=\"$status\"",
            "reason=\"$reason\"",
        ).joinToString(separator = "") { "(?=[^}]*${Regex.escape(it)})" }
        return requireNotNull(
            Regex("""leader_backend_connectivity_total\{${labels}[^}]*}\s+([0-9.Ee+-]+)""")
                .find(this),
        ) {
            "connectivity sample is missing for $backendName/$status/$reason"
        }.groupValues[1].toDouble()
    }

    companion object {
        private const val EXPORTED_LOCK_NAME = LeaderMetricTagOptions.DEFAULT_LOCK_NAME_REDACTED_VALUE
        private val redis = RedisServer.Launcher.redis
        private val httpClient = HttpClient.newHttpClient()

        @JvmStatic
        @DynamicPropertySource
        fun redisProperties(registry: DynamicPropertyRegistry) {
            registry.add("demo.redis.url") { redis.url }
        }
    }
}
