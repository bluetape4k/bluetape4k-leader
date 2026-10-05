package io.bluetape4k.leader.examples.prometheus

import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
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
        log.debug { "prometheus config: $prometheusConfig" }
        prometheusConfig shouldContain "rule_files:"
        prometheusConfig shouldContain "/etc/prometheus/rules/leader-alerts.yml"

        val compose = composePath.readText()
        log.debug { "compose: $compose" }
        compose shouldContain "./provisioning/prometheus/rules:/etc/prometheus/rules:ro"
    }

    @Test
    fun `leader alert rules cover operational failure modes`() {
        val rules = prometheusRulesPath.readText()

        expectedAlerts.forEach { alert ->
            rules shouldContain "alert: $alert"
        }

        rules shouldContain """leader_aop_lock_not_acquired_total{reason="BACKEND_ERROR"}"""
        rules shouldContain "leader_aop_task_failed_total"
        rules shouldContain """leader_history_sink_failures_total{sink!="NoopLeaderHistorySink"}"""
        rules shouldContain """leader_history_acquire_missing_total{sink!="NoopLeaderHistorySink"}"""
        rules shouldContain """leader_aop_active{lock_name="dashboard-job"} > 1"""
        rules shouldContain "leader_aop_execution_duration_seconds_sum"
        rules shouldContain "leader_backend_connectivity_total"
        rules shouldContain """leader_backend_connectivity_total{status="DOWN",reason="DISCONNECTED"}"""
        rules shouldContain
                """leader_backend_connectivity_total{status="UNKNOWN",reason=~"CLIENT_STATE_UNCONFIRMED|PROVIDER_UNSUPPORTED"}"""

        rules shouldContain
                """leader_backend_connectivity_total{status="UNKNOWN",reason="PROVIDER_EXCEPTION"}"""

        rules shouldContain "notification: no-page"
        rules shouldContain "for: 5m"
        rules shouldContain "for: 10m"
        rules shouldContain """absent(up{job="bluetape4k-leader"}) or up{job="bluetape4k-leader"} == 0"""

        rules shouldContain
                "https://github.com/bluetape4k/bluetape4k-leader/blob/develop/" +
                "examples/prometheus-dashboard/README.md#alert-runbooks"
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
            dashboard shouldContain """"title": "$panelTitle""""
        }

        dashboard shouldContain "clamp_min"
        dashboard shouldContain """leader_aop_lock_not_acquired_total{reason=\"BACKEND_ERROR\"}"""
        dashboard shouldContain "max by (lock_name) (leader_aop_active)"
    }

    @Test
    fun `application config redacts lock name metric tags by default`() {
        val config = applicationConfigPath.readText()

        config shouldContain "mode: REDACT"
        config shouldContain "redacted-value: redacted-lock"
        config shouldNotContain "mode: RAW"
        config shouldContain "backend-probe:"
        config shouldContain "DEMO_BACKEND_PROBE_FIXED_DELAY_MS"
        config shouldContain "DEMO_BACKEND_PROBE_INITIAL_DELAY_MS"
        config shouldContain "DEMO_BACKEND_PROBE_TIMEOUT_MS"
    }

    @Test
    fun `readme files document alerts runbooks and diagram`() {
        val english = englishReadmePath.readText()
        val korean = koreanReadmePath.readText()

        Files.exists(alertRunbookDiagramSvgPath).shouldBeTrue()
        Files.exists(alertRunbookDiagramPath).shouldBeTrue()
        alertRunbookDiagramSvgPath.readText() shouldContain "Prometheus Alert And Runbook Flow"
        alertRunbookDiagramSvgPath.readText() shouldContain "data-connector=\"observe-only-note\""

        listOf(english, korean).forEach { readme ->
            readme shouldContain "examples-prometheus-dashboard-alert-runbook-01.png"
            readme shouldContain "leader-alerts.yml"
            readme shouldContain "LeaderElectionBackendErrors"
            readme shouldContain "LeaderBackendConnectivityDown"
            readme shouldContain "LeaderBackendConnectivityUnknown"
            readme shouldContain "LeaderBackendConnectivityProbeExceptions"
            readme shouldContain "leader_backend_connectivity_total"
            readme shouldContain "PrometheusBackendConnectivityProbe"
            readme shouldContain "DEMO_BACKEND_PROBE_TIMEOUT_MS"
            readme shouldContain "PROVIDER_EXCEPTION"
            readme shouldContain "LeaderHistorySinkFailures"
            readme shouldContain "max by (lock_name) (leader_aop_active)"
        }
    }

    private fun Path.readText(): String = Files.readString(this)

    companion object: KLogging() {
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
