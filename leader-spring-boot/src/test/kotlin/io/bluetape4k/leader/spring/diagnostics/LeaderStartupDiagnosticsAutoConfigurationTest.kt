package io.bluetape4k.leader.spring.diagnostics

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderLease
import io.bluetape4k.leader.LeaderState
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.leader.spring.LeaderElectionAutoConfiguration
import io.bluetape4k.leader.spring.backend.LocalLeaderConfiguration
import io.bluetape4k.leader.spring.observability.LeaderElectionActuatorAutoConfiguration
import io.bluetape4k.leader.spring.observability.LeaderElectionObservabilityAutoConfiguration
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.getBean
import org.springframework.beans.factory.getBeansOfType
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import io.bluetape4k.assertions.shouldBeFalse

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LeaderStartupDiagnosticsAutoConfigurationTest {

    private val runner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                LeaderElectionAutoConfiguration::class.java,
                LocalLeaderConfiguration::class.java,
                LeaderElectionObservabilityAutoConfiguration::class.java,
                LeaderElectionActuatorAutoConfiguration::class.java,
                LeaderStartupDiagnosticsAutoConfiguration::class.java,
            )
        )

    @Test
    fun `default local backend context registers diagnostics report`() {
        runner.run { ctx ->
            val diagnostics = ctx.getBean<LeaderStartupDiagnostics>()
            val report = diagnostics.lastReport().shouldNotBeNull()

            report.activeBackends shouldBeEqualTo listOf("local")
            report.warningCodes shouldBeEqualTo emptyList()
            report.strict.shouldBeFalse()

            report.leaderElectorBeans shouldContain "localLeaderElector"
        }
    }

    @Test
    fun `diagnostics can be disabled`() {
        runner
            .withPropertyValues("bluetape4k.leader.diagnostics.enabled=false")
            .run { ctx ->
                ctx.getBeansOfType<LeaderStartupDiagnostics>().isEmpty().shouldBeTrue()
            }
    }

    @Test
    fun `multiple non-local leader electors record a non-fatal warning by default`() {
        runner
            .withUserConfiguration(MultipleNonLocalLeaderElectorConfig::class.java)
            .run { ctx ->
                val report = ctx.getBean<LeaderStartupDiagnostics>().lastReport().shouldNotBeNull()

                report.warningCodes shouldContain LeaderStartupDiagnostics.WarningCode.MULTIPLE_NON_LOCAL_BACKENDS.name
                report.activeBackends shouldBeEqualTo listOf("custom-a", "custom-b")
            }
    }

    @Test
    fun `invalid explicit state provider fails startup instead of being silently ignored`() {
        runner
            .withPropertyValues("bluetape4k.leader.observability.state-provider-bean=missingStateProvider")
            .run { ctx ->
                ctx.startupFailure.shouldNotBeNull()
                    .shouldBeInstanceOf<IllegalStateException>()
            }
    }

    @Test
    fun `suspend-only backend replaces local fallback in diagnostics`() {
        runner
            .withUserConfiguration(SuspendOnlyBackendConfig::class.java)
            .run { ctx ->
                val report = ctx.getBean<LeaderStartupDiagnostics>().lastReport().shouldNotBeNull()

                report.activeBackends shouldBeEqualTo listOf("exposed-r2dbc")
            }
    }

    @Test
    fun `strict diagnostics fails startup when warnings exist`() {
        runner
            .withUserConfiguration(MultipleNonLocalLeaderElectorConfig::class.java)
            .withPropertyValues("bluetape4k.leader.diagnostics.strict=true")
            .run { ctx ->
                ctx.startupFailure.shouldNotBeNull()
                    .shouldBeInstanceOf<LeaderStartupDiagnosticsException>()
            }
    }

    @Test
    fun `enabled actuator endpoint without web exposure records warning`() {
        runner
            .withPropertyValues("management.endpoint.leaderElection.enabled=true")
            .run { ctx ->
                val report = ctx.getBean<LeaderStartupDiagnostics>().lastReport().shouldNotBeNull()

                report.warningCodes shouldContain LeaderStartupDiagnostics.WarningCode.MANAGEMENT_ENDPOINT_NOT_EXPOSED.name
            }
    }

    @Test
    fun `enabled actuator endpoint without seeded registry records warning`() {
        runner
            .withPropertyValues(
                "management.endpoint.leaderElection.enabled=true",
                "management.endpoints.web.exposure.include=health,leaderElection",
            )
            .run { ctx ->
                val report = ctx.getBean<LeaderStartupDiagnostics>().lastReport().shouldNotBeNull()

                report.warningCodes shouldContain LeaderStartupDiagnostics.WarningCode.MANAGEMENT_REGISTRY_NOT_SEEDED.name
            }
    }

    @Test
    fun `seeded actuator endpoint avoids management diagnostics warnings`() {
        runner
            .withPropertyValues(
                "bluetape4k.leader.observability.lock-names[0]=batch-job",
                "management.endpoint.leaderElection.enabled=true",
                "management.endpoints.web.exposure.include=health,leaderElection",
            )
            .run { ctx ->
                val report = ctx.getBean<LeaderStartupDiagnostics>().lastReport().shouldNotBeNull()

                report.warningCodes shouldBeEqualTo emptyList()
            }
    }

    @Test
    fun `raw lock name metrics without allow list records cardinality warning`() {
        runner
            .withPropertyValues("bluetape4k.leader.aop.metrics.tags.lock-name.mode=RAW")
            .run { ctx ->
                val report = ctx.getBean<LeaderStartupDiagnostics>().lastReport().shouldNotBeNull()

                report.warningCodes shouldContain LeaderStartupDiagnostics.WarningCode.RAW_LOCK_NAME_TAGS.name
            }
    }

    @Configuration(proxyBeanMethods = false)
    class MultipleNonLocalLeaderElectorConfig {

        @Bean("customA")
        fun customA(): LeaderElector =
            NamedLeaderElector("custom-a")

        @Bean("customB")
        fun customB(): LeaderElector =
            NamedLeaderElector("custom-b")
    }

    @Configuration(proxyBeanMethods = false)
    class SuspendOnlyBackendConfig {
        @Bean("exposedR2dbcSuspendLeaderElector")
        fun exposedR2dbcSuspendLeaderElector(): SuspendLeaderElector = NamedSuspendLeaderElector()
    }

    private class NamedLeaderElector(private val backendName: String) : LeaderElector {

        override fun <T> runIfLeader(lockName: String, action: () -> T): T? =
            action()

        override fun <T> runAsyncIfLeader(
            lockName: String,
            executor: Executor,
            action: () -> CompletableFuture<T>,
        ): CompletableFuture<T?> =
            action().thenApply { it }

        override fun state(lockName: String): LeaderState =
            LeaderState.occupied(
                lockName = lockName,
                leader = LeaderLease(
                    auditLeaderId = backendName,
                    leaseUntil = Instant.parse("2026-07-03T00:00:00Z"),
                ),
            )
    }


    private class NamedSuspendLeaderElector : SuspendLeaderElector {
        override val supportsAuditLeaderState: Boolean = true

        override suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T? = action()

        override fun state(lockName: String): LeaderState = LeaderState.empty(lockName)
    }
}
