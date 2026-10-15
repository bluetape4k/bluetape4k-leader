package io.bluetape4k.leader.strategy.strategies

import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.ElectionResult
import io.bluetape4k.leader.strategy.ElectionStrategy
import io.bluetape4k.leader.strategy.Elimination
import kotlin.random.Random

/**
 * 후보를 `nodeId` 순으로 정렬한 뒤 하나를 무작위로 뽑습니다. seed를 지정하면 같은 입력에서 선택을 재현할 수 있습니다.
 * @property seed 반복 실행 간 선택을 재현할 optional seed. `null`이면 기본 random generator를 사용합니다.
 */
class RandomElectionStrategy(val seed: Long? = null): ElectionStrategy {

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
