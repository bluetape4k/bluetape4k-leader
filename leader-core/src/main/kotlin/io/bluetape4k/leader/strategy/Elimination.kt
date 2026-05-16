package io.bluetape4k.leader.strategy

/**
 * Holds a candidate eliminated from the election and the reason for elimination.
 *
 * @property candidate the eliminated candidate
 * @property reason reason for elimination (human-readable description)
 */
data class Elimination(
    val candidate: CandidateInfo,
    val reason: String,
)
