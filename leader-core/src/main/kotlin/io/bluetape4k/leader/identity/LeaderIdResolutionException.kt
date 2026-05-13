package io.bluetape4k.leader.identity

/**
 * Thrown when all fallback levels of the leader ID resolution chain produce a blank result.
 *
 * ## Contract
 * **MUST be rethrown** regardless of `failureMode` — see spec §D7.
 * This exception always escapes the aspect, even in `FAIL_OPEN_RUN` mode.
 * It is never silently swallowed.
 *
 * ## Usage
 * ```kotlin
 * throw LeaderIdResolutionException(
 *     "All fallback levels returned blank: literal='', SpEL skipped, property='', provider=''"
 * )
 * ```
 */
class LeaderIdResolutionException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
