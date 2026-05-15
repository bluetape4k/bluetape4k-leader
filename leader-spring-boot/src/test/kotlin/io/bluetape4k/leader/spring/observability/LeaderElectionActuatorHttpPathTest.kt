package io.bluetape4k.leader.spring.observability

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderLease
import io.bluetape4k.leader.LeaderState
import io.bluetape4k.leader.spring.LeaderTestApplication
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

@SpringBootTest(
    classes = [
        LeaderTestApplication::class,
        LeaderElectionActuatorHttpPathTest.TestLeaderElectorConfig::class,
    ],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "management.endpoint.leaderElection.enabled=true",
        "management.endpoints.web.exposure.include=leaderElection",
        "bluetape4k.leader.observability.lock-names[0]=batch-job",
    ],
)
@ImportAutoConfiguration(
    LeaderElectionObservabilityAutoConfiguration::class,
    LeaderElectionActuatorAutoConfiguration::class,
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LeaderElectionActuatorHttpPathTest {

    @LocalServerPort
    private var port: Int = 0

    private val httpClient = HttpClient.newHttpClient()

    @Test
    fun `actuator endpoint is exposed at camel-case leaderElection path`() {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:$port/actuator/leaderElection"))
            .GET()
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

        response.statusCode() shouldBeEqualTo 200
        response.body().shouldContain("\"name\":\"batch-job\"")
        response.body().shouldContain("\"status\":\"Occupied\"")
        response.body().shouldContain("\"leaderId\":\"node-1\"")
        response.body().shouldContain("\"leaseExpiry\":\"2026-05-16T00:00:00Z\"")
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    class TestLeaderElectorConfig {
        @Bean
        fun testLeaderElector(): LeaderElector =
            TestLeaderElector()
    }

    private class TestLeaderElector : LeaderElector {

        override fun <T> runIfLeader(lockName: String, action: () -> T): T? =
            action()

        override fun <T> runAsyncIfLeader(
            lockName: String,
            executor: Executor,
            action: () -> CompletableFuture<T>,
        ): CompletableFuture<T?> =
            action().thenApply { it }

        override fun state(lockName: String): LeaderState =
            if (lockName == "batch-job") {
                LeaderState.occupied(
                    lockName = lockName,
                    leader = LeaderLease(
                        auditLeaderId = "node-1",
                        leaseUntil = Instant.parse("2026-05-16T00:00:00Z"),
                    )
                )
            } else {
                LeaderState.empty(lockName)
            }
    }
}
