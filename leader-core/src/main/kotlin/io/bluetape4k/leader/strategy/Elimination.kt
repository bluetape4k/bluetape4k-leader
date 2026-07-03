package io.bluetape4k.leader.strategy

import java.io.Serializable

/**
 * Holds a candidate eliminated from the election and the reason for elimination.
 *
 * @property candidate the eliminated candidate
 * @property reason reason for elimination (human-readable description)
 */
data class Elimination(
    val candidate: CandidateInfo,
    val reason: String,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
