package io.bluetape4k.leader.identity

/**
 * Provides a unique leader identity string for a given lock name.
 *
 * ## Contract
 * - MUST NOT throw under any circumstance.
 * - MUST NOT block the calling thread.
 * - MUST be thread-safe.
 * - MUST return a non-blank string.
 *
 * ## Usage
 * ```kotlin
 * val provider: LeaderIdProvider = RandomLeaderIdProvider.Default
 * val leaderId = provider.nextLeaderId("my-lock")
 * ```
 */
fun interface LeaderIdProvider {
    /**
     * Returns a unique non-blank leader identity for the given [lockName].
     */
    fun nextLeaderId(lockName: String): String
}
