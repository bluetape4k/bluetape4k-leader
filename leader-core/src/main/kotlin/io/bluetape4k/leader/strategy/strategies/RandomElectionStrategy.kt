package io.bluetape4k.leader.strategy.strategies

import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.ElectionResult
import io.bluetape4k.leader.strategy.ElectionStrategy
import io.bluetape4k.leader.strategy.Elimination
import kotlin.random.Random

/**
 * An [ElectionStrategy] that elects a candidate at random.
 *
 * ## Behavior / Contract
 * - When [seed] is provided, results are deterministic for the same candidate list.
 * - Candidates are sorted by [CandidateInfo.nodeId] before random selection to remove
 *   input-order dependency.
 *
 * **Distributed-environment warning**: a `null` [seed] means each node may compute a different
 * winner (split-brain risk). In distributed deployments, all nodes must share the same [seed].
 * Generate the seed from a shared backend (e.g., Redis) on a per-epoch basis.
 * Use `seed=null` only for single-process tests or when outcome distribution is not critical.
 *
 * @property seed random seed. `null` uses system random (non-deterministic — not recommended in distributed environments).
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
