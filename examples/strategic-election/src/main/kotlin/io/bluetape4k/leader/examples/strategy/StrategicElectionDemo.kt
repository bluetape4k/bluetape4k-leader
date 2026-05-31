package io.bluetape4k.leader.examples.strategy

import io.bluetape4k.leader.local.LocalStrategicLeaderElector
import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.CandidateScorer
import io.bluetape4k.leader.strategy.scorers.IdleTimeScorer
import io.bluetape4k.leader.strategy.scorers.SuccessRateScorer
import io.bluetape4k.leader.strategy.scorers.WeightedScorer
import io.bluetape4k.leader.strategy.strategies.ScoredElectionStrategy
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import java.io.Serializable
import java.time.Instant

/**
 * Backend-neutral weighted strategic-election demo.
 *
 * The scenario models three service nodes that can run a maintenance job. The
 * elected node is chosen by combining service health, available capacity,
 * historical success rate, and idle time. Only the elected node executes the
 * action; all other nodes return a skip report.
 */
object StrategicElectionDemo: KLogging() {

    private const val DEFAULT_LOCK_NAME = "service-maintenance"

    @JvmStatic
    fun main(args: Array<String>) {
        val report = runScenario()

        log.info { "=== strategic election demo result ===" }
        log.info { "selected=${report.selectedNodeId}, scores=${report.scores}" }
        report.nodeReports.forEach { nodeReport ->
            log.info { "[${nodeReport.nodeId}] ${nodeReport.status}" }
        }
    }

    fun runScenario(
        profiles: List<ServiceNodeProfile> = defaultProfiles(),
        lockName: String = DEFAULT_LOCK_NAME,
    ): StrategicElectionReport {
        val strategy = ScoredElectionStrategy(defaultScorer())
        val electors = profiles.map { LocalStrategicLeaderElector(it.nodeId) }
        val candidates = profiles.map { it.toCandidateInfo() }

        electors.forEach { elector ->
            candidates.forEach { candidate -> elector.registerCandidate(lockName, candidate) }
        }

        val scores = strategy.elect(candidates).scores
        val nodeReports = electors.map { elector ->
            elector.runIfLeader(lockName, strategy) {
                StrategicNodeReport(
                    nodeId = elector.nodeId,
                    status = StrategicElectionStatus.SELECTED,
                )
            } ?: StrategicNodeReport(
                nodeId = elector.nodeId,
                status = StrategicElectionStatus.SKIPPED,
            )
        }

        val selectedNodeId = nodeReports.singleOrNull { it.status == StrategicElectionStatus.SELECTED }?.nodeId
        return StrategicElectionReport(
            selectedNodeId = selectedNodeId,
            nodeReports = nodeReports,
            scores = scores,
        )
    }

    fun defaultScorer(): WeightedScorer =
        WeightedScorer(
            ServiceReadinessScorer to 0.50,
            SuccessRateScorer to 0.35,
            IdleTimeScorer to 0.15,
        )

    fun defaultProfiles(): List<ServiceNodeProfile> {
        val now = Instant.parse("2026-06-01T00:00:00Z")
        return listOf(
            ServiceNodeProfile(
                nodeId = "node-a",
                registeredAt = now.minusSeconds(90),
                lastCompletionTime = now.minusSeconds(15),
                healthPercent = 92,
                availableCapacityPercent = 35,
                successCount = 18,
                failureCount = 2,
            ),
            ServiceNodeProfile(
                nodeId = "node-b",
                registeredAt = now.minusSeconds(120),
                lastCompletionTime = now.minusSeconds(80),
                healthPercent = 86,
                availableCapacityPercent = 88,
                successCount = 14,
                failureCount = 1,
            ),
            ServiceNodeProfile(
                nodeId = "node-c",
                registeredAt = now.minusSeconds(60),
                lastCompletionTime = now.minusSeconds(240),
                healthPercent = 52,
                availableCapacityPercent = 95,
                successCount = 8,
                failureCount = 7,
            ),
        )
    }
}

object ServiceReadinessScorer: CandidateScorer {

    override fun score(candidate: CandidateInfo, all: List<CandidateInfo>): Double {
        val health = candidate.metadata.getPercent("healthPercent")
        val capacity = candidate.metadata.getPercent("availableCapacityPercent")
        return health * 0.60 + capacity * 0.40
    }

    private fun Map<String, String>.getPercent(key: String): Double =
        get(key)?.toDoubleOrNull()?.coerceIn(0.0, 100.0) ?: 0.0
}

data class ServiceNodeProfile(
    val nodeId: String,
    val registeredAt: Instant,
    val lastCompletionTime: Instant,
    val healthPercent: Int,
    val availableCapacityPercent: Int,
    val successCount: Long,
    val failureCount: Long,
): Serializable {

    fun toCandidateInfo(): CandidateInfo =
        CandidateInfo(
            nodeId = nodeId,
            registeredAt = registeredAt,
            lastCompletionTime = lastCompletionTime,
            successCount = successCount,
            failureCount = failureCount,
            metadata = mapOf(
                "healthPercent" to healthPercent.toString(),
                "availableCapacityPercent" to availableCapacityPercent.toString(),
            ),
        )

    companion object {
        private const val serialVersionUID: Long = -8268623493084262118L
    }
}

enum class StrategicElectionStatus {
    SELECTED,
    SKIPPED,
}

data class StrategicNodeReport(
    val nodeId: String,
    val status: StrategicElectionStatus,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = -2167886996176127354L
    }
}

data class StrategicElectionReport(
    val selectedNodeId: String?,
    val nodeReports: List<StrategicNodeReport>,
    val scores: Map<String, Double>,
): Serializable {

    val selectedCount: Int
        get() = nodeReports.count { it.status == StrategicElectionStatus.SELECTED }

    val skippedCount: Int
        get() = nodeReports.count { it.status == StrategicElectionStatus.SKIPPED }

    companion object {
        private const val serialVersionUID: Long = 5631294745847230078L
    }
}
