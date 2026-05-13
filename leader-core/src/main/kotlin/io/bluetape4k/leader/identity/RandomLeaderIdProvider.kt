package io.bluetape4k.leader.identity

import io.bluetape4k.codec.Base58
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireGt

/**
 * [LeaderIdProvider] that generates random Base58 strings as leader identities.
 *
 * ## Contract
 * - Each call to [nextLeaderId] returns a statistically unique string of [length] Base58 characters.
 * - Default length is [DefaultLength] (12 characters, ~70 bits of entropy).
 * - Thread-safe: Base58 generation is stateless.
 *
 * ## Usage
 * ```kotlin
 * val provider = RandomLeaderIdProvider()            // length 12
 * val custom   = RandomLeaderIdProvider(length = 16) // longer
 * val leaderId = provider.nextLeaderId("my-lock")    // e.g. "aBcDeFgHiJkL"
 * ```
 */
class RandomLeaderIdProvider(val length: Int = DefaultLength) : LeaderIdProvider {

    init {
        length.requireGt(0, "length")
    }

    companion object : KLogging() {
        /** Default generated string length (12 Base58 characters ≈ 70 bits of entropy). */
        const val DefaultLength: Int = 12

        /** Shared default instance. Thread-safe. */
        @JvmField
        val Default: LeaderIdProvider = RandomLeaderIdProvider()
    }

    override fun nextLeaderId(lockName: String): String = Base58.randomString(length)
}
