package io.bluetape4k.leader.spring.observability

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.leader.LeaderElectionEvent
import io.bluetape4k.leader.LeaderElectionEventPublisher
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderLease
import io.bluetape4k.leader.LeaderState
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.getBean
import org.springframework.beans.factory.getBeansOfType
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LeaderElectionObservabilityAutoConfigurationTest {

    private val runner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                LeaderElectionObservabilityAutoConfiguration::class.java,
                LeaderElectionActuatorAutoConfiguration::class.java,
                LeaderElectionReadinessHealthAutoConfiguration::class.java,
            )
        )
        .withUserConfiguration(TestLeaderElectorConfig::class.java)

    @Test
    fun `registry is seeded from configured lock names`() {
        runner
            .withPropertyValues(
                "bluetape4k.leader.observability.lock-names[0]=batch-job",
                "bluetape4k.leader.observability.lock-names[1]=migration-gate",
            )
            .run { ctx ->
                val registry = ctx.getBean<LeaderElectionStatusRegistry>()

                registry.snapshot() shouldBeEqualTo listOf("batch-job", "migration-gate")
            }
    }

    @Test
    fun `fallback event publisher facade is registered when elector is not publisher aware`() {
        runner.run { ctx ->
            val publisher = ctx.getBean<LeaderElectionEventPublisher>()

            publisher shouldBeInstanceOf LeaderElectionObservedEventPublisher::class
        }
    }

    @Test
    fun `actuator endpoint is disabled by default`() {
        runner.run { ctx ->
            ctx.getBeansOfType<LeaderElectionStatusEndpoint>().isEmpty().shouldBeTrue()
        }
    }

    @Test
    fun `actuator endpoint is registered when endpoint property is enabled`() {
        runner
            .withPropertyValues("management.endpoint.leaderElection.enabled=true")
            .run { ctx ->
                ctx.getBean<LeaderElectionStatusEndpoint>().shouldNotBeNull()
            }
    }

    @Test
    fun `actuator endpoint stays absent when no state provider is registered`() {
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    LeaderElectionObservabilityAutoConfiguration::class.java,
                    LeaderElectionActuatorAutoConfiguration::class.java,
                )
            )
            .withPropertyValues("management.endpoint.leaderElection.enabled=true")
            .run { ctx ->
                ctx.getBeansOfType<LeaderElectionStatusEndpoint>().isEmpty().shouldBeTrue()
            }
    }

    @Test
    fun `actuator endpoint returns known lock response shape`() {
        runner
            .withPropertyValues(
                "management.endpoint.leaderElection.enabled=true",
                "bluetape4k.leader.observability.lock-names[0]=batch-job",
            )
            .run { ctx ->
                val endpoint = ctx.getBean<LeaderElectionStatusEndpoint>()
                val response = endpoint.leaderElectionStatus()

                response.locks.size shouldBeEqualTo 1
                response.locks[0].name shouldBeEqualTo "batch-job"
                response.locks[0].status shouldBeEqualTo "Occupied"
                response.locks[0].leaderId shouldBeEqualTo "node-1"
                response.locks[0].leaseExpiry shouldBeEqualTo TestLeaderElector.LeaseUntil
                response.backend shouldBeEqualTo "test"
                response.stateProviderBean shouldBeEqualTo "testLeaderElector"
                response.stateSupported.shouldBeTrue()
            }
    }

    @Test
    fun `actuator selects non-local suspend state provider over local blocking fallback`() {
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    LeaderElectionObservabilityAutoConfiguration::class.java,
                    LeaderElectionActuatorAutoConfiguration::class.java,
                )
            )
            .withUserConfiguration(LocalAndSuspendElectorConfig::class.java)
            .withPropertyValues(
                "management.endpoint.leaderElection.enabled=true",
                "bluetape4k.leader.observability.lock-names[0]=batch-job",
            )
            .run { ctx ->
                val response = ctx.getBean<LeaderElectionStatusEndpoint>().leaderElectionStatus()

                response.locks.single().status shouldBeEqualTo "Occupied"
                response.locks.single().leaderId shouldBeEqualTo "r2dbc-node"
            }
    }

    @Test
    fun `unsupported suspend backend is represented explicitly`() {
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    LeaderElectionObservabilityAutoConfiguration::class.java,
                    LeaderElectionActuatorAutoConfiguration::class.java,
                )
            )
            .withUserConfiguration(UnsupportedSuspendElectorConfig::class.java)
            .withPropertyValues(
                "management.endpoint.leaderElection.enabled=true",
                "bluetape4k.leader.observability.lock-names[0]=batch-job",
            )
            .run { ctx ->
                val response = ctx.getBean<LeaderElectionStatusEndpoint>().leaderElectionStatus()

                response.backend shouldBeEqualTo "exposed-r2dbc"
                response.stateProviderBean shouldBeEqualTo "exposedR2dbcSuspendLeaderElector"
                response.stateSupported.shouldBeFalse()
                response.locks.single().status shouldBeEqualTo "Unsupported"
            }
    }

    @Test
    fun `explicit state provider selects one backend in multi-backend context`() {
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    LeaderElectionObservabilityAutoConfiguration::class.java,
                    LeaderElectionActuatorAutoConfiguration::class.java,
                )
            )
            .withUserConfiguration(MultipleElectorConfig::class.java)
            .withPropertyValues(
                "management.endpoint.leaderElection.enabled=true",
                "bluetape4k.leader.observability.state-provider-bean=backendBLeaderElector",
                "bluetape4k.leader.observability.lock-names[0]=batch-job",
            )
            .run { ctx ->
                val response = ctx.getBean<LeaderElectionStatusEndpoint>().leaderElectionStatus()

                response.locks.single().leaderId shouldBeEqualTo "backend-b"
            }
    }

    @Test
    fun `readiness health indicator is disabled by default`() {
        runner.run { ctx ->
            ctx.getBeansOfType<HealthIndicator>().isEmpty().shouldBeTrue()
        }
    }

    @Test
    fun `readiness health indicator is registered when explicitly enabled`() {
        runner
            .withPropertyValues(
                "bluetape4k.leader.observability.health.enabled=true",
                "bluetape4k.leader.observability.health.lease-warning-threshold=15s",
            )
            .run { ctx ->
                ctx.getBean("leaderElectionReadiness", HealthIndicator::class.java).shouldNotBeNull()
                ctx.getBean<io.bluetape4k.leader.spring.LeaderProperties>()
                    .observability.health.leaseWarningThreshold shouldBeEqualTo java.time.Duration.ofSeconds(15)
            }
    }

    @Test
    fun `observed event publisher emits events and registers observed lock names`() = runTest {
        val registry = LeaderElectionStatusRegistry()
        val publisher = LeaderElectionObservedEventPublisher(registry)
        val events = async { publisher.events.take(3).toList() }
        runCurrent()

        publisher.onElected("job-a")
        publisher.onSkipped("job-b")
        publisher.onRevoked("job-a")

        events.await() shouldBeEqualTo listOf(
            LeaderElectionEvent.Elected("job-a"),
            LeaderElectionEvent.Skipped("job-b"),
            LeaderElectionEvent.Revoked("job-a"),
        )
        registry.snapshot() shouldBeEqualTo listOf("job-a", "job-b")
    }

    @Configuration(proxyBeanMethods = false)
    class TestLeaderElectorConfig {
        @Bean
        fun testLeaderElector(): LeaderElector =
            TestLeaderElector()
    }

    @Configuration(proxyBeanMethods = false)
    class LocalAndSuspendElectorConfig {
        @Bean("localLeaderElector")
        fun localLeaderElector(): LeaderElector = TestLeaderElector("local-node")

        @Bean("exposedR2dbcSuspendLeaderElector")
        fun exposedR2dbcSuspendLeaderElector(): SuspendLeaderElector = TestSuspendLeaderElector("r2dbc-node")
    }

    @Configuration(proxyBeanMethods = false)
    class MultipleElectorConfig {
        @Bean("backendALeaderElector")
        fun backendALeaderElector(): LeaderElector = TestLeaderElector("backend-a")

        @Bean("backendBLeaderElector")
        fun backendBLeaderElector(): LeaderElector = TestLeaderElector("backend-b")
    }

    @Configuration(proxyBeanMethods = false)
    class UnsupportedSuspendElectorConfig {
        @Bean("localLeaderElector")
        fun localLeaderElector(): LeaderElector = TestLeaderElector("local-node")

        @Bean("exposedR2dbcSuspendLeaderElector")
        fun exposedR2dbcSuspendLeaderElector(): SuspendLeaderElector = UnsupportedSuspendLeaderElector()
    }

    private class TestLeaderElector(
        private val leaderId: String = "node-1",
    ) : LeaderElector {

        companion object {
            val LeaseUntil: Instant = Instant.parse("2026-05-16T00:00:00Z")
        }

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
                        auditLeaderId = leaderId,
                        leaseUntil = LeaseUntil,
                    )
                )
            } else {
                LeaderState.empty(lockName)
            }

        override val supportsAuditLeaderState: Boolean = true
    }

    private class TestSuspendLeaderElector(
        private val leaderId: String,
    ) : SuspendLeaderElector {
        override val supportsAuditLeaderState: Boolean = true

        override suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T? = action()

        override fun state(lockName: String): LeaderState = LeaderState.occupied(
            lockName,
            LeaderLease(auditLeaderId = leaderId, leaseUntil = TestLeaderElector.LeaseUntil),
        )
    }

    private class UnsupportedSuspendLeaderElector : SuspendLeaderElector {
        override suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T? = action()
    }
}
