package io.bluetape4k.leader.redisson

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.CandidateResult
import io.bluetape4k.leader.strategy.scorers.SuccessRateScorer
import io.bluetape4k.leader.strategy.strategies.ScoredElectionStrategy
import io.bluetape4k.leader.strategy.strategies.ScoredGroupElectionStrategy
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class RedissonStrategicHeartbeatTest : AbstractRedissonLeaderTest() {

    private val registeredAt = Instant.parse("2026-01-01T00:00:00Z")
    private val lastStartTime = Instant.parse("2026-01-01T00:00:30Z")
    private val staleCompletion = Instant.parse("2026-01-01T00:01:00Z")

    private fun candidate(
        nodeId: String,
        successCount: Long,
        failureCount: Long,
        metadata: String,
    ) = CandidateInfo(
        nodeId = nodeId,
        registeredAt = registeredAt,
        lastStartTime = lastStartTime,
        lastCompletionTime = staleCompletion,
        successCount = successCount,
        failureCount = failureCount,
        metadata = mapOf("source" to metadata),
    )

    private fun assertHeartbeatResult(
        candidate: CandidateInfo,
        expectedSuccessCount: Long,
        expectedFailureCount: Long,
        expectedMetadata: String,
    ) {
        candidate.registeredAt shouldBeEqualTo registeredAt
        candidate.lastStartTime shouldBeEqualTo lastStartTime
        (candidate.lastCompletionTime?.isAfter(staleCompletion) == true).shouldBeTrue()
        candidate.successCount shouldBeEqualTo expectedSuccessCount
        candidate.failureCount shouldBeEqualTo expectedFailureCount
        candidate.metadata shouldBeEqualTo mapOf("source" to expectedMetadata)
    }

    @Test
    fun `blocking single heartbeat preserves counters for both operation orderings`() {
        val lockName = randomName()
        val elector = RedissonStrategicLeaderElector(redissonClient, "node-1")
        val node1 = candidate("node-1", successCount = 4, failureCount = 1, metadata = "stale-1")
        val node2 = candidate("node-2", successCount = 2, failureCount = 3, metadata = "stale-2")

        elector.registerCandidate(lockName, node1, ttl = 5.seconds)
        elector.registerCandidate(lockName, node2, ttl = 5.seconds)
        elector.updateResult(lockName, "node-1", CandidateResult.SUCCESS)
        elector.refreshCandidate(lockName, node1.copy(metadata = mapOf("source" to "fresh-1")), ttl = 5.seconds)
        elector.refreshCandidate(lockName, node2.copy(metadata = mapOf("source" to "fresh-2")), ttl = 5.seconds)
        elector.updateResult(lockName, "node-2", CandidateResult.FAILURE)

        val candidates = elector.listCandidates(lockName)
        assertHeartbeatResult(candidates.first { it.nodeId == "node-1" }, 5L, 1L, "fresh-1")
        assertHeartbeatResult(candidates.first { it.nodeId == "node-2" }, 2L, 4L, "fresh-2")
        ScoredElectionStrategy(SuccessRateScorer).elect(candidates).winner?.nodeId shouldBeEqualTo "node-1"
    }

    @Test
    fun `blocking group heartbeat preserves counters for both operation orderings`() {
        val lockName = randomName()
        val elector = RedissonStrategicLeaderGroupElector(redissonClient, "node-1")
        val node1 = candidate("node-1", successCount = 4, failureCount = 1, metadata = "stale-1")
        val node2 = candidate("node-2", successCount = 2, failureCount = 3, metadata = "stale-2")

        elector.registerCandidate(lockName, node1, ttl = 5.seconds)
        elector.registerCandidate(lockName, node2, ttl = 5.seconds)
        elector.updateResult(lockName, "node-1", CandidateResult.SUCCESS)
        elector.refreshCandidate(lockName, node1.copy(metadata = mapOf("source" to "fresh-1")), ttl = 5.seconds)
        elector.refreshCandidate(lockName, node2.copy(metadata = mapOf("source" to "fresh-2")), ttl = 5.seconds)
        elector.updateResult(lockName, "node-2", CandidateResult.FAILURE)

        val candidates = elector.listCandidates(lockName)
        assertHeartbeatResult(candidates.first { it.nodeId == "node-1" }, 5L, 1L, "fresh-1")
        assertHeartbeatResult(candidates.first { it.nodeId == "node-2" }, 2L, 4L, "fresh-2")
        ScoredGroupElectionStrategy(SuccessRateScorer)
            .elect(candidates, maxLeaders = 1)
            .winners
            .single()
            .nodeId shouldBeEqualTo "node-1"
    }

    @Test
    fun `suspend single heartbeat preserves counters for both operation orderings`() = runSuspendIO {
        val lockName = randomName()
        val elector = RedissonStrategicSuspendLeaderElector(redissonClient, "node-1")
        val node1 = candidate("node-1", successCount = 4, failureCount = 1, metadata = "stale-1")
        val node2 = candidate("node-2", successCount = 2, failureCount = 3, metadata = "stale-2")

        elector.registerCandidate(lockName, node1, ttl = 5.seconds)
        elector.registerCandidate(lockName, node2, ttl = 5.seconds)
        elector.updateResult(lockName, "node-1", CandidateResult.SUCCESS)
        elector.refreshCandidate(lockName, node1.copy(metadata = mapOf("source" to "fresh-1")), ttl = 5.seconds)
        elector.refreshCandidate(lockName, node2.copy(metadata = mapOf("source" to "fresh-2")), ttl = 5.seconds)
        elector.updateResult(lockName, "node-2", CandidateResult.FAILURE)

        val candidates = elector.listCandidates(lockName)
        assertHeartbeatResult(candidates.first { it.nodeId == "node-1" }, 5L, 1L, "fresh-1")
        assertHeartbeatResult(candidates.first { it.nodeId == "node-2" }, 2L, 4L, "fresh-2")
        ScoredElectionStrategy(SuccessRateScorer).elect(candidates).winner?.nodeId shouldBeEqualTo "node-1"
    }

    @Test
    fun `suspend group heartbeat preserves counters for both operation orderings`() = runSuspendIO {
        val lockName = randomName()
        val elector = RedissonStrategicSuspendLeaderGroupElector(redissonClient, "node-1")
        val node1 = candidate("node-1", successCount = 4, failureCount = 1, metadata = "stale-1")
        val node2 = candidate("node-2", successCount = 2, failureCount = 3, metadata = "stale-2")

        elector.registerCandidate(lockName, node1, ttl = 5.seconds)
        elector.registerCandidate(lockName, node2, ttl = 5.seconds)
        elector.updateResult(lockName, "node-1", CandidateResult.SUCCESS)
        elector.refreshCandidate(lockName, node1.copy(metadata = mapOf("source" to "fresh-1")), ttl = 5.seconds)
        elector.refreshCandidate(lockName, node2.copy(metadata = mapOf("source" to "fresh-2")), ttl = 5.seconds)
        elector.updateResult(lockName, "node-2", CandidateResult.FAILURE)

        val candidates = elector.listCandidates(lockName)
        assertHeartbeatResult(candidates.first { it.nodeId == "node-1" }, 5L, 1L, "fresh-1")
        assertHeartbeatResult(candidates.first { it.nodeId == "node-2" }, 2L, 4L, "fresh-2")
        ScoredGroupElectionStrategy(SuccessRateScorer)
            .elect(candidates, maxLeaders = 1)
            .winners
            .single()
            .nodeId shouldBeEqualTo "node-1"
    }

    @Test
    fun `refreshCandidate는 TTL을 재설정하지만 만료된 후보를 되살리지 않는다`() {
        val lockName = randomName()
        val elector = RedissonStrategicLeaderElector(redissonClient, "node-1")
        val stale = candidate("node-1", successCount = 1, failureCount = 0, metadata = "stale")
        val cache = redissonClient.getMapCache<String, CandidateInfo>(
            "${RedissonCandidateRegistry.DEFAULT_KEY_PREFIX}:$lockName",
        )

        elector.registerCandidate(lockName, stale, ttl = 800.milliseconds)
        val beforeUpdate = cache.remainTimeToLive("node-1")
        elector.updateResult(lockName, "node-1", CandidateResult.SUCCESS)
        val afterUpdate = cache.remainTimeToLive("node-1")
        (beforeUpdate > 0L && afterUpdate > 0L && afterUpdate <= beforeUpdate + 50L).shouldBeTrue()

        elector.refreshCandidate(lockName, stale.copy(metadata = mapOf("source" to "fresh")), ttl = 2.seconds)
        val afterRefresh = cache.remainTimeToLive("node-1")
        (afterRefresh > afterUpdate).shouldBeTrue()

        cache.remove("node-1")
        elector.refreshCandidate(lockName, stale.copy(metadata = mapOf("source" to "late")), ttl = 2.seconds)
        elector.listCandidates(lockName).isEmpty().shouldBeTrue()
    }
}
