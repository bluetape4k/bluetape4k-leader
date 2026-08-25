package io.bluetape4k.leader.strategy

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.leader.strategy.strategies.FifoGroupElectionStrategy
import io.bluetape4k.leader.strategy.strategies.ScoredGroupElectionStrategy
import org.junit.jupiter.api.Test
import java.time.Instant

class StrategicGroupElectionStrategyTest {

    private val t0 = Instant.parse("2026-01-01T00:00:00Z")

    private fun candidate(
        id: String,
        registeredAt: Instant = t0,
        successCount: Long = 0,
        failureCount: Long = 0,
    ) = CandidateInfo(
        nodeId = id,
        registeredAt = registeredAt,
        successCount = successCount,
        failureCount = failureCount,
    )

    @Test
    fun `FIFO는 maxLeaders 순서대로 winner를 선택하고 나머지를 elimination으로 분리한다`() {
        val candidates = listOf(
            candidate("c", t0.plusSeconds(20)),
            candidate("a", t0),
            candidate("b", t0.plusSeconds(10)),
        )

        val result = FifoGroupElectionStrategy.elect(candidates, maxLeaders = 2)

        result.winners.map(CandidateInfo::nodeId) shouldBeEqualTo listOf("a", "b")
        result.eliminations.map { it.candidate.nodeId } shouldBeEqualTo listOf("c")
        result.scores.isEmpty().shouldBeTrue()
    }

    @Test
    fun `FIFO는 registeredAt 동률을 nodeId 사전순으로 결정한다`() {
        val candidates = listOf(
            candidate("z"),
            candidate("a"),
            candidate("m"),
        )

        val result = FifoGroupElectionStrategy.elect(candidates, maxLeaders = 2)

        result.winners.map(CandidateInfo::nodeId) shouldBeEqualTo listOf("a", "m")
        result.eliminations.single().candidate.nodeId shouldBeEqualTo "z"
    }

    @Test
    fun `후보가 없으면 EMPTY 결과를 반환한다`() {
        FifoGroupElectionStrategy.elect(emptyList(), maxLeaders = 2) shouldBeEqualTo
            StrategicGroupElectionResult.EMPTY
    }

    @Test
    fun `후보 수가 maxLeaders보다 작으면 모든 후보를 선택한다`() {
        val candidates = listOf(candidate("a"), candidate("b", t0.plusSeconds(1)))

        val result = FifoGroupElectionStrategy.elect(candidates, maxLeaders = 5)

        result.winners.map(CandidateInfo::nodeId) shouldBeEqualTo listOf("a", "b")
        result.eliminations.isEmpty().shouldBeTrue()
    }

    @Test
    fun `scored 전략은 score 내림차순 후 등록 시각과 nodeId로 tie-break한다`() {
        val candidates = listOf(
            candidate("later", t0.plusSeconds(10), successCount = 10),
            candidate("tie-b", t0.plusSeconds(5), successCount = 5),
            candidate("tie-a", t0, successCount = 5),
        )
        val strategy = ScoredGroupElectionStrategy(CandidateScorer { it, _ -> it.successCount.toDouble() })

        val result = strategy.elect(candidates, maxLeaders = 2)

        result.winners.map(CandidateInfo::nodeId) shouldBeEqualTo listOf("later", "tie-a")
        result.eliminations.single().candidate.nodeId shouldBeEqualTo "tie-b"
        result.scores shouldBeEqualTo mapOf("later" to 10.0, "tie-b" to 5.0, "tie-a" to 5.0)
    }

    @Test
    fun `maxLeaders가 0이면 Bluetape 검증 예외를 반환한다`() {
        assertFailsWith<IllegalArgumentException> {
            FifoGroupElectionStrategy.elect(listOf(candidate("a")), maxLeaders = 0)
        }
    }

    @Test
    fun `scorer가 NaN을 반환하면 전략을 거부한다`() {
        assertFailsWith<IllegalArgumentException> {
            ScoredGroupElectionStrategy(CandidateScorer { _, _ -> Double.NaN })
                .elect(listOf(candidate("a")), maxLeaders = 1)
        }
    }

    @Test
    fun `electValidated는 결과가 후보 전체를 분할하지 않으면 거부한다`() {
        val invalidStrategy = GroupElectionStrategy { _, _ ->
            StrategicGroupElectionResult(
                winners = listOf(candidate("a")),
                eliminations = emptyList(),
            )
        }

        assertFailsWith<IllegalArgumentException> {
            invalidStrategy.electValidated(listOf(candidate("a"), candidate("b")), maxLeaders = 1)
        }
    }
}
