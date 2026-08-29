package io.bluetape4k.leader.examples.prometheus

import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.nio.file.Path

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PrometheusAssetsTest {

    @Test
    fun `prometheus config wires leader alert rules`() {
        Files.exists(prometheusRulesPath).shouldBeTrue()

        val prometheusConfig = prometheusConfigPath.readText()
        prometheusConfig.contains("rule_files:").shouldBeTrue()
        prometheusConfig.contains("/etc/prometheus/rules/leader-alerts.yml").shouldBeTrue()

        val compose = composePath.readText()
        compose.contains("./provisioning/prometheus/rules:/etc/prometheus/rules:ro").shouldBeTrue()
    }

    @Test
    fun `leader alert rules cover operational failure modes`() {
        val rules = prometheusRulesPath.readText()

        expectedAlerts.forEach { alert ->
            rules.contains("alert: $alert").shouldBeTrue()
        }

        rules.contains("""leader_aop_lock_not_acquired_total{reason="BACKEND_ERROR"}""").shouldBeTrue()
        rules.contains("leader_aop_task_failed_total").shouldBeTrue()
        rules.contains("""leader_history_sink_failures_total{sink!="NoopLeaderHistorySink"}""").shouldBeTrue()
        rules.contains("""leader_history_acquire_missing_total{sink!="NoopLeaderHistorySink"}""").shouldBeTrue()
        rules.contains("""leader_aop_active{lock_name="dashboard-job"} > 1""").shouldBeTrue()
        rules.contains("leader_aop_execution_duration_seconds_sum").shouldBeTrue()
        rules.contains("leader_backend_connectivity_total").shouldBeTrue()
        rules.contains("""leader_backend_connectivity_total{status="DOWN",reason="DISCONNECTED"}""").shouldBeTrue()
        rules.contains(
            """leader_backend_connectivity_total{status="UNKNOWN",reason=~"CLIENT_STATE_UNCONFIRMED|PROVIDER_UNSUPPORTED"}""",
        ).shouldBeTrue()
        rules.contains(
            """leader_backend_connectivity_total{status="UNKNOWN",reason="PROVIDER_EXCEPTION"}""",
        ).shouldBeTrue()
        rules.contains("notification: no-page").shouldBeTrue()
        rules.contains("for: 5m").shouldBeTrue()
        rules.contains("for: 10m").shouldBeTrue()
        rules.contains("""absent(up{job="bluetape4k-leader"}) or up{job="bluetape4k-leader"} == 0""")
            .shouldBeTrue()
        rules.contains(
            "https://github.com/bluetape4k/bluetape4k-leader/blob/develop/" +
                "examples/prometheus-dashboard/README.md#alert-runbooks",
        ).shouldBeTrue()
    }

    @Test
    fun `grafana dashboard includes alert oriented panels`() {
        val dashboard = grafanaDashboardPath.readText()

        listOf(
            "Acquisition Success Ratio",
            "Backend Error Rate",
            "Task Failure Rate",
            "History Sink Signals",
            "Lease Risk",
        ).forEach { panelTitle ->
            dashboard.contains(""""title": "$panelTitle"""").shouldBeTrue()
        }

        dashboard.contains("clamp_min").shouldBeTrue()
        dashboard.contains("""leader_aop_lock_not_acquired_total{reason=\"BACKEND_ERROR\"}""").shouldBeTrue()
        dashboard.contains("max by (lock_name) (leader_aop_active)").shouldBeTrue()
    }

    @Test
    fun `application config redacts lock name metric tags by default`() {
        val config = applicationConfigPath.readText()

        config.contains("mode: REDACT").shouldBeTrue()
        config.contains("redacted-value: redacted-lock").shouldBeTrue()
        config.contains("mode: RAW").shouldBeFalse()
        config.contains("backend-probe:").shouldBeTrue()
        config.contains("DEMO_BACKEND_PROBE_FIXED_DELAY_MS").shouldBeTrue()
        config.contains("DEMO_BACKEND_PROBE_INITIAL_DELAY_MS").shouldBeTrue()
        config.contains("DEMO_BACKEND_PROBE_TIMEOUT_MS").shouldBeTrue()
    }

    @Test
    fun `readme files document alerts runbooks and diagram`() {
        val english = englishReadmePath.readText()
        val korean = koreanReadmePath.readText()

        Files.exists(alertRunbookDiagramSvgPath).shouldBeTrue()
        Files.exists(alertRunbookDiagramPath).shouldBeTrue()
        alertRunbookDiagramSvgPath.readText().contains("Prometheus Alert And Runbook Flow").shouldBeTrue()
        alertRunbookDiagramSvgPath.readText().contains("data-connector=\"observe-only-note\"").shouldBeTrue()

        listOf(english, korean).forEach { readme ->
            readme.contains("examples-prometheus-dashboard-alert-runbook-01.png").shouldBeTrue()
            readme.contains("leader-alerts.yml").shouldBeTrue()
            readme.contains("LeaderElectionBackendErrors").shouldBeTrue()
            readme.contains("LeaderBackendConnectivityDown").shouldBeTrue()
            readme.contains("LeaderBackendConnectivityUnknown").shouldBeTrue()
            readme.contains("LeaderBackendConnectivityProbeExceptions").shouldBeTrue()
            readme.contains("leader_backend_connectivity_total").shouldBeTrue()
            readme.contains("PrometheusBackendConnectivityProbe").shouldBeTrue()
            readme.contains("DEMO_BACKEND_PROBE_TIMEOUT_MS").shouldBeTrue()
            readme.contains("PROVIDER_EXCEPTION").shouldBeTrue()
            readme.contains("LeaderHistorySinkFailures").shouldBeTrue()
            readme.contains("max by (lock_name) (leader_aop_active)").shouldBeTrue()
        }
    }

    private fun Path.readText(): String = Files.readString(this)

    companion object {
        private val projectRoot = findProjectRoot(Path.of("").toAbsolutePath().normalize())
        private val exampleRoot = projectRoot.resolve("examples/prometheus-dashboard")
        private val docsImageRoot = projectRoot.resolve("docs/images/readme-diagrams")

        private val prometheusConfigPath = exampleRoot.resolve("provisioning/prometheus/prometheus.yml")
        private val applicationConfigPath = exampleRoot.resolve("src/main/resources/application.yml")
        private val prometheusRulesPath = exampleRoot.resolve("provisioning/prometheus/rules/leader-alerts.yml")
        private val grafanaDashboardPath =
            exampleRoot.resolve("provisioning/grafana/dashboards/leader-dashboard.json")
        private val composePath = exampleRoot.resolve("docker-compose.yml")
        private val englishReadmePath = exampleRoot.resolve("README.md")
        private val koreanReadmePath = exampleRoot.resolve("README.ko.md")
        private val alertRunbookDiagramSvgPath =
            docsImageRoot.resolve("examples-prometheus-dashboard-alert-runbook-01.svg")
        private val alertRunbookDiagramPath =
            docsImageRoot.resolve("examples-prometheus-dashboard-alert-runbook-01.png")

        private val expectedAlerts = listOf(
            "LeaderElectionNoAcquisitions",
            "LeaderElectionBackendErrors",
            "LeaderElectionTaskFailures",
            "LeaderHistorySinkFailures",
            "LeaderHistoryAcquireMissing",
            "LeaderActiveGaugeAnomaly",
            "LeaderLeaseRiskHighExecutionTime",
            "LeaderPrometheusScrapeMissing",
            "LeaderBackendConnectivityDown",
            "LeaderBackendConnectivityUnknown",
            "LeaderBackendConnectivityProbeExceptions",
        )

        private fun findProjectRoot(start: Path): Path =
            generateSequence(start) { it.parent }
                .first { Files.exists(it.resolve("settings.gradle.kts")) }
    }
}
