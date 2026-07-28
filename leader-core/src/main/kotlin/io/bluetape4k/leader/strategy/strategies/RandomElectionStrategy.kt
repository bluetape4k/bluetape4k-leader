package io.bluetape4k.leader.strategy.strategies

import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.ElectionResult
import io.bluetape4k.leader.strategy.ElectionStrategy
import io.bluetape4k.leader.strategy.Elimination
import kotlin.random.Random

/**
 * `RandomElectionStrategy`는 전략 기반 leader 선출에서 후보 평가 규칙을 제공합니다.
 *
 * API 이름과 `lock`, `lease`, `leader`, `slot`, `audit` 용어는 코드 계약과 동일하게 유지합니다.
 * @property seed random 선출을 재현 가능하게 만드는 optional seed입니다.
 */
class RandomElectionStrategy(val seed: Long? = null) : ElectionStrategy {

    override fun elect(candidates: List<CandidateInfo>): ElectionResult {
        if (candidates.isEmpty()) return ElectionResult.EMPTY
        val sorted = candidates.sortedBy(CandidateInfo::nodeId)
        val random = if (seed != null) Random(seed) else Random.Default
        val winner = sorted[random.nextInt(sorted.size)]
        val eliminations = candidates
            .filter { it.nodeId != winner.nodeId }
            .map { c -> Elimination(c, "not selected by random election (winner: ${winner.nodeId})") }
        return ElectionResult(winner, eliminations)
    }
}
