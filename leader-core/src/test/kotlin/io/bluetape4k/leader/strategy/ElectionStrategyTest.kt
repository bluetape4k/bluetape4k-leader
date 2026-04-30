package io.bluetape4k.leader.strategy

import io.bluetape4k.leader.strategy.scorers.IdleTimeScorer
import io.bluetape4k.leader.strategy.scorers.SuccessRateScorer
import io.bluetape4k.leader.strategy.scorers.WeightedScorer
import io.bluetape4k.leader.strategy.strategies.FifoElectionStrategy
import io.bluetape4k.leader.strategy.strategies.RandomElectionStrategy
import io.bluetape4k.leader.strategy.strategies.ScoredElectionStrategy
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ElectionStrategyTest {

    private val t0 = Instant.parse("2026-01-01T00:00:00Z")
    private val t1 = t0.plusSeconds(10)
    private val t2 = t0.plusSeconds(20)

    private fun candidate(
        id: String,
        registeredAt: Instant = t0,
        lastCompletionTime: Instant? = null,
        successCount: Long = 0,
        failureCount: Long = 0,
    ) = CandidateInfo(
        nodeId = id,
        registeredAt = registeredAt,
        lastCompletionTime = lastCompletionTime,
        successCount = successCount,
        failureCount = failureCount,
    )

    // ── FifoElectionStrategy ──────────────────────────────────────────────────

    @Test
    fun `FIFO - 가장 먼저 등록된 후보 선출`() {
        val candidates = listOf(
            candidate("c", registeredAt = t2),
            candidate("a", registeredAt = t0),
            candidate("b", registeredAt = t1),
        )
        FifoElectionStrategy.elect(candidates).winner?.nodeId shouldBeEqualTo "a"
    }

    @Test
    fun `FIFO - registeredAt 동일 시 nodeId 사전순 선출`() {
        val candidates = listOf(
            candidate("z", registeredAt = t0),
            candidate("a", registeredAt = t0),
        )
        FifoElectionStrategy.elect(candidates).winner?.nodeId shouldBeEqualTo "a"
    }

    @Test
    fun `FIFO - 후보 1명이면 해당 후보 반환`() {
        val candidates = listOf(candidate("solo"))
        FifoElectionStrategy.elect(candidates).winner?.nodeId shouldBeEqualTo "solo"
    }

    @Test
    fun `FIFO - 후보 없으면 null 반환`() {
        FifoElectionStrategy.elect(emptyList()).winner.shouldBeNull()
    }

    @Test
    fun `FIFO - 탈락자에 등록 시각 늦음 사유 포함`() {
        val candidates = listOf(
            candidate("a", registeredAt = t0),
            candidate("b", registeredAt = t1),
            candidate("c", registeredAt = t2),
        )
        val result = FifoElectionStrategy.elect(candidates)
        result.winner?.nodeId shouldBeEqualTo "a"
        result.eliminations.size shouldBeEqualTo 2
        result.eliminations.all { it.reason.contains("등록 시각 늦음") }.shouldBeTrue()
    }

    // ── RandomElectionStrategy ────────────────────────────────────────────────

    @Test
    fun `Random - 동일 seed 동일 결과`() {
        val candidates = listOf(candidate("a"), candidate("b"), candidate("c"))
        val strategy = RandomElectionStrategy(seed = 42L)
        val r1 = strategy.elect(candidates).winner
        val r2 = strategy.elect(candidates).winner
        r1.shouldNotBeNull()
        r1.nodeId shouldBeEqualTo r2!!.nodeId
    }

    @Test
    fun `Random - 후보 없으면 null 반환`() {
        RandomElectionStrategy().elect(emptyList()).winner.shouldBeNull()
    }

    @Test
    fun `Random - 탈락자에 랜덤 선출 탈락 사유 포함`() {
        val candidates = listOf(candidate("a"), candidate("b"), candidate("c"))
        val result = RandomElectionStrategy(seed = 42L).elect(candidates)
        result.winner.shouldNotBeNull()
        result.eliminations.size shouldBeEqualTo 2
        result.eliminations.all { it.reason.contains("랜덤 선출 탈락") }.shouldBeTrue()
    }

    // ── ScoredElectionStrategy (IdleTimeScorer) ───────────────────────────────

    @Test
    fun `Scored IdleTime - 가장 오래 쉰 후보 선출`() {
        val now = Instant.now()
        val candidates = listOf(
            candidate("recent", lastCompletionTime = now.minusSeconds(10)),
            candidate("idle", lastCompletionTime = now.minusSeconds(100)),
            candidate("medium", lastCompletionTime = now.minusSeconds(50)),
        )
        ScoredElectionStrategy(IdleTimeScorer)
            .elect(candidates).winner?.nodeId shouldBeEqualTo "idle"
    }

    @Test
    fun `Scored IdleTime - 미실행 노드는 registeredAt 기준 경과 시간 적용`() {
        val now = Instant.now()
        val candidates = listOf(
            candidate("never-ran", registeredAt = now.minusSeconds(200)),
            candidate("ran-recently", lastCompletionTime = now.minusSeconds(10)),
        )
        ScoredElectionStrategy(IdleTimeScorer)
            .elect(candidates).winner?.nodeId shouldBeEqualTo "never-ran"
    }

    // ── ScoredElectionStrategy (SuccessRateScorer) ────────────────────────────

    @Test
    fun `Scored SuccessRate - 성공률 높은 후보 선출`() {
        val candidates = listOf(
            candidate("low", successCount = 1, failureCount = 9),   // 10%
            candidate("high", successCount = 9, failureCount = 1),  // 90%
            candidate("mid", successCount = 5, failureCount = 5),   // 50%
        )
        ScoredElectionStrategy(SuccessRateScorer)
            .elect(candidates).winner?.nodeId shouldBeEqualTo "high"
    }

    @Test
    fun `Scored SuccessRate - 탈락자에 점수 미달 사유 포함`() {
        val candidates = listOf(
            candidate("low", successCount = 1, failureCount = 9),
            candidate("high", successCount = 9, failureCount = 1),
        )
        val result = ScoredElectionStrategy(SuccessRateScorer).elect(candidates)
        result.winner?.nodeId shouldBeEqualTo "high"
        result.eliminations.size shouldBeEqualTo 1
        result.eliminations.first().reason.contains("점수 미달").shouldBeTrue()
    }

    // ── ScoredElectionStrategy (WeightedScorer) ───────────────────────────────

    @Test
    fun `Scored Weighted - 복합 점수 최고 후보 선출`() {
        val now = Instant.now()
        val candidates = listOf(
            candidate("success-focused", lastCompletionTime = now.minusSeconds(5), successCount = 10),
            candidate("idle-focused", lastCompletionTime = now.minusSeconds(500), successCount = 0, failureCount = 10),
        )
        val scorer = WeightedScorer(IdleTimeScorer to 0.1, SuccessRateScorer to 0.9)
        ScoredElectionStrategy(scorer).elect(candidates).winner?.nodeId shouldBeEqualTo "success-focused"
    }

    @Test
    fun `Scored - 후보 없으면 null 반환`() {
        ScoredElectionStrategy(IdleTimeScorer).elect(emptyList()).winner.shouldBeNull()
    }
}
