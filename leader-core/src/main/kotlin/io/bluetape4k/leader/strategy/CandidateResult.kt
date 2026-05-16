package io.bluetape4k.leader.strategy

/**
 * Represents the execution result of a task run by the elected leader node.
 */
enum class CandidateResult {
    /** Task succeeded. */
    SUCCESS,

    /** Task failed (including exception thrown). */
    FAILURE,
}
