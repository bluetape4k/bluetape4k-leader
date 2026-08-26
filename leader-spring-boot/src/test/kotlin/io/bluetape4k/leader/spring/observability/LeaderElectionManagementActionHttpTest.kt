package io.bluetape4k.leader.spring.observability

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderLeaseHandle
import io.bluetape4k.leader.LeaderManagementActionRegistry
import io.bluetape4k.leader.LeaderState
import io.bluetape4k.leader.LeaseOwnershipStatus
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
        LeaderElectionManagementActionHttpTest.TestConfiguration::class,
    ],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "management.endpoint.leaderElection.enabled=true",
        "management.endpoint.leaderElection.actions.enabled=true",
        "management.endpoints.web.exposure.include=leaderElectionActions",
    ],
)
@ImportAutoConfiguration(
    LeaderElectionObservabilityAutoConfiguration::class,
    LeaderElectionActuatorAutoConfiguration::class,
    LeaderElectionManagementActionAutoConfiguration::class,
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LeaderElectionManagementActionHttpTest {

    @LocalServerPort
    private var port: Int = 0

    private val httpClient = HttpClient.newHttpClient()

    @Test
    fun `POST action uses shared status mapping and sanitized response`() {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:$port/actuator/leaderElectionActions/batch-job"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.noBody())
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

        response.statusCode() shouldBeEqualTo 200
        response.body().shouldContain("\"action\":\"RELEASE\"")
        response.body().shouldContain("\"outcome\":\"RELEASED\"")
        response.body().shouldContain("\"mutationAttempted\":true")
        response.body().contains("lockName").shouldBeEqualTo(false)
        response.body().contains("token").shouldBeEqualTo(false)
    }

    @Test
    fun `invalid selector is rejected without invoking registry backend`() {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:$port/actuator/leaderElectionActions/bad%25job"))
            .POST(HttpRequest.BodyPublishers.noBody())
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

        response.statusCode() shouldBeEqualTo 400
        response.body().shouldContain("\"outcome\":\"INVALID_LOCK_NAME\"")
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    class TestConfiguration {
        @Bean
        fun testLeaderElector(): LeaderElector = TestLeaderElector()

        @Bean
        fun leaderManagementActionRegistry(): LeaderManagementActionRegistry =
            LeaderManagementActionRegistry().also { registry ->
                registry.register(TestHandle("batch-job"))
            }
    }

    private class TestLeaderElector : LeaderElector {
        override fun <T> runIfLeader(lockName: String, action: () -> T): T? = action()

        override fun <T> runAsyncIfLeader(
            lockName: String,
            executor: Executor,
            action: () -> CompletableFuture<T>,
        ): CompletableFuture<T?> = action().thenApply { it }

        override fun state(lockName: String): LeaderState = LeaderState.empty(lockName)
    }

    private class TestHandle(
        override val lockName: String,
    ) : LeaderLeaseHandle {
        private var ownershipCalls = 0

        override val auditLeaderId: String = "test-leader"
        override val acquiredAt: Instant = Instant.EPOCH

        override fun extend(lockAtMostFor: kotlin.time.Duration): ExtendOutcome = ExtendOutcome.NotHeld

        override fun ownershipStatus(): LeaseOwnershipStatus =
            if (ownershipCalls++ == 0) LeaseOwnershipStatus.HELD else LeaseOwnershipStatus.NOT_HELD

        override fun isStillHeld(): Boolean = ownershipStatus() == LeaseOwnershipStatus.HELD

        override fun release() = Unit
    }
}
