package io.bluetape4k.leader

import io.bluetape4k.logging.KLogging

/**
 * Result type of [LeaderElector.runIfLeaderResult] / [LeaderGroupElector.runIfLeaderResult].
 *
 * ## Purpose
 * [LeaderElector.runIfLeader] returns `null` for both (a) lock not acquired and (b) action returning null,
 * making it impossible for callers to distinguish the two cases.
 * This type eliminates that ambiguity.
 *
 * ## States
 * - [Elected]: leader election succeeded — action was executed. Even if action returned null, [Elected] is returned.
 * - [Skipped]: leader election failed — action was not executed because the lock was not acquired.
 */
sealed interface LeaderRunResult<out T> {

    companion object : KLogging()

    /**
     * Leader election succeeded — action executed to completion.
     *
     * Action return value may be null; this still classifies as [Elected].
     *
     * NOTE: Even though T may be a non-null type, [value] is declared as `T?`
     * due to constraints of the closure pattern through `runIfLeader()` return type `T?`.
     *
     * @property value the return value of the executed action; may be null even on success.
     * @property leaderId the audit identity of the elected leader, if available.
     *
     * ## Usage
     * ```kotlin
     * // 1-arg construction (backward compat)
     * val r1 = LeaderRunResult.Elected(42)
     *
     * // 2-arg construction with leaderId
     * val r2 = LeaderRunResult.Elected("done", leaderId = "node-a")
     *
     * // Destructuring
     * val (v) = r1   // v = 42
     * ```
     */
    data class Elected<out T> @JvmOverloads constructor(
        val value: T?,
        val leaderId: String? = null,
    ) : LeaderRunResult<T>

    /** Leader election failed — action was not executed because the lock was not acquired. */
    data object Skipped : LeaderRunResult<Nothing>
}
