package io.bluetape4k.leader.examples.prometheus

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.testcontainers.infra.PrometheusServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.testcontainers.utility.MountableFile
import java.nio.file.Files
import java.nio.file.Path

class PrometheusAlertRulesTest {

    @Test
    fun `promtool evaluates connectivity alerts and runbook annotations`(@TempDir tempDir: Path) {
        val testRules = tempDir.resolve("leader-alert-rules-test.yml")
        Files.copy(testRulesResource, testRules)

        PrometheusServer(tag = PROMETHEUS_TAG, reuse = false).apply {
            withCopyFileToContainer(
                MountableFile.forHostPath(prometheusRulesPath),
                "/tmp/leader-alerts.yml",
            )
            withCopyFileToContainer(
                MountableFile.forHostPath(testRules),
                "/tmp/leader-alert-rules-test.yml",
            )
            start()
        }.use { prometheus ->
            val result = prometheus.execInContainer(
                "/bin/promtool",
                "test",
                "rules",
                "/tmp/leader-alert-rules-test.yml",
            )

            result.exitCode shouldBeEqualTo 0
            result.stdout shouldContain "SUCCESS"
        }
    }

    companion object {
        private const val PROMETHEUS_TAG = "v2.55.1"
        private val projectRoot = findProjectRoot(Path.of("").toAbsolutePath().normalize())
        private val prometheusRulesPath =
            projectRoot.resolve("examples/prometheus-dashboard/provisioning/prometheus/rules/leader-alerts.yml")
        private val testRulesResource =
            PrometheusAlertRulesTest::class.java.getResource("/prometheus/leader-alert-rules-test.yml")
                ?.toURI()
                ?.let(Path::of)
                ?: error("Prometheus alert rule test resource is missing")

        private fun findProjectRoot(start: Path): Path =
            generateSequence(start) { it.parent }
                .first { Files.exists(it.resolve("settings.gradle.kts")) }
    }
}
