package io.bluetape4k.leader.examples.prometheus

import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.leader.micrometer.LeaderMetricTagOptions
import io.bluetape4k.testcontainers.storage.RedisServer
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.aop.support.AopUtils
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "demo.job.fixed-delay-ms=200",
        "demo.job.initial-delay-ms=0",
    ],
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PrometheusScrapeTest {

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var leaderScheduledJob: LeaderScheduledJob

    @Test
    fun `actuator prometheus exposes leader AOP metrics`() {
        AopUtils.isAopProxy(leaderScheduledJob).shouldBeTrue()

        await.atMost(Duration.ofSeconds(30))
            .untilAsserted {
                val scrape = scrapePrometheus()

                scrape.hasLockNameSeries("leader_aop_attempts_total").shouldBeTrue()
                scrape.hasLockNameSeries("leader_aop_acquired_total").shouldBeTrue()
                scrape.hasLockNameSeries("leader_aop_active").shouldBeTrue()
                scrape.contains("""lock_name="${LeaderScheduledJob.LOCK_NAME}"""").shouldBeFalse()
                scrape.contains("""leader_history_sink_failures_total{sink="NoopLeaderHistorySink"}""")
                    .shouldBeTrue()
                scrape.contains("""leader_history_acquire_missing_total{sink="NoopLeaderHistorySink"}""")
                    .shouldBeTrue()

                val attempts = scrape.sampleValue("leader_aop_attempts_total")
                val acquired = scrape.sampleValue("leader_aop_acquired_total")

                attempts shouldBeGreaterThan 0.0
                acquired shouldBeGreaterThan 0.0
            }
    }

    private fun scrapePrometheus(): String {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:$port/actuator/prometheus"))
            .timeout(Duration.ofSeconds(5))
            .GET()
            .build()

        return httpClient.send(request, HttpResponse.BodyHandlers.ofString()).body()
    }

    private fun String.hasLockNameSeries(metricName: String): Boolean {
        val lockNameTag = Regex.escape("""lock_name="$EXPORTED_LOCK_NAME"""")
        return Regex("""$metricName\{[^}]*$lockNameTag[^}]*}\s+[0-9.Ee+-]+""").containsMatchIn(this)
    }

    private fun String.sampleValue(metricName: String): Double {
        val regex = Regex("""$metricName\{[^}]*lock_name="$EXPORTED_LOCK_NAME"[^}]*}\s+([0-9.Ee+-]+)""")
        return requireNotNull(regex.find(this)) {
            "$metricName for $EXPORTED_LOCK_NAME not found in scrape"
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
