package io.bluetape4k.leader.examples.prometheus

import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.assertions.shouldBeTrue
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

                scrape.contains("""leader_aop_attempts_total{lock_name="${LeaderScheduledJob.LOCK_NAME}"""")
                    .shouldBeTrue()
                scrape.contains("""leader_aop_acquired_total{lock_name="${LeaderScheduledJob.LOCK_NAME}"""")
                    .shouldBeTrue()
                scrape.contains("""leader_aop_active{lock_name="${LeaderScheduledJob.LOCK_NAME}"""")
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

    private fun String.sampleValue(metricName: String): Double {
        val regex = Regex("""$metricName\{[^}]*lock_name="${LeaderScheduledJob.LOCK_NAME}"[^}]*}\s+([0-9.Ee+-]+)""")
        return requireNotNull(regex.find(this)) {
            "$metricName for ${LeaderScheduledJob.LOCK_NAME} not found in scrape"
        }.groupValues[1].toDouble()
    }

    companion object {
        private val redis = RedisServer.Launcher.redis
        private val httpClient = HttpClient.newHttpClient()

        @JvmStatic
        @DynamicPropertySource
        fun redisProperties(registry: DynamicPropertyRegistry) {
            registry.add("demo.redis.url") { redis.url }
        }
    }
}
