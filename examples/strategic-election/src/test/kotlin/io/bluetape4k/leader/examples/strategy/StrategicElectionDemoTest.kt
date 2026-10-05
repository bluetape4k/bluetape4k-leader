package io.bluetape4k.leader.examples.strategy

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.junit.jupiter.api.Test
import java.time.Instant

class StrategicElectionDemoTest {

    companion object: KLogging()

    @Test
    fun `weighted strategy selects the best maintenance node`() {
        val report = StrategicElectionDemo.runScenario()

        log.debug { "report=$report" }
        report.selectedNodeId shouldBeEqualTo "node-b"
        report.selectedCount shouldBeEqualTo 1
        report.skippedCount shouldBeEqualTo 2
        report.scores["node-b"].shouldNotBeNull() shouldBeEqualTo report.scores.values.maxOrNull()
    }

    @Test
    fun `all non-winner nodes return skipped reports`() {
        val report = StrategicElectionDemo.runScenario()

        log.debug { "report=$report" }
        report.nodeReports
            .filterNot { it.nodeId == report.selectedNodeId }
            .map { it.status }
            .toSet() shouldBeEqualTo setOf(StrategicElectionStatus.SKIPPED)
    }

    @Test
    fun `custom scorer uses metadata percentages`() {
        val now = Instant.parse("2026-06-01T00:00:00Z")
        val report = StrategicElectionDemo.runScenario(
            profiles = listOf(
                ServiceNodeProfile(
                    nodeId = "healthy",
                    registeredAt = now.minusSeconds(30),
                    lastCompletionTime = now.minusSeconds(30),
                    healthPercent = 100,
                    availableCapacityPercent = 100,
                    successCount = 1,
                    failureCount = 0,
                ),
                ServiceNodeProfile(
                    nodeId = "unhealthy",
                    registeredAt = now.minusSeconds(60),
                    lastCompletionTime = now.minusSeconds(60),
                    healthPercent = 0,
                    availableCapacityPercent = 0,
                    successCount = 0,
                    failureCount = 1,
                ),
            ),
        )

        log.debug { "report=$report" }
        report.selectedNodeId shouldBeEqualTo "healthy"
        report.selectedCount shouldBeEqualTo 1
        report.skippedCount shouldBeEqualTo 1
    }

    @Test
    fun `custom scorer falls back to zero for missing metadata`() {
        val candidate = CandidateInfo("node-without-metadata")
        ServiceReadinessScorer.score(candidate, listOf(candidate)) shouldBeEqualTo 0.0
    }
}
