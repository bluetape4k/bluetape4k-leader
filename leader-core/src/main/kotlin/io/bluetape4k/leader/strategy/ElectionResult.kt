package io.bluetape4k.leader.strategy

/**
 * Represents the result of an election.
 *
 * @property winner the elected leader. `null` if there are no candidates.
 * @property eliminations list of eliminated candidates and each elimination reason.
 * @property scores per-candidate scores (nodeId → score). Populated only by score-based strategies.
 */
data class ElectionResult(
    val winner: CandidateInfo?,
    val eliminations: List<Elimination>,
    val scores: Map<String, Double> = emptyMap(),
) {
    companion object {
        /** No candidates — empty result with no winner and no eliminations. */
        val EMPTY = ElectionResult(winner = null, eliminations = emptyList())
    }
}
