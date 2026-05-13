package io.bluetape4k.leader.identity

import io.bluetape4k.codec.Base58
import io.bluetape4k.leader.LeaderNodeId
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireGt

/**
 * [LeaderIdProvider] that combines the current node's hostname+PID with a random suffix.
 *
 * ## Contract
 * - Format: `"${LeaderNodeId.Default}:${Base58.randomString(suffixLength)}"`
 * - Each call returns a unique value (random suffix prevents repeated-startup collisions).
 *
 * ## PII Warning
 * The hostname portion may be sensitive in multi-tenant SaaS environments where
 * hostnames identify customers or internal infrastructure. **Do NOT use in such contexts.**
 * Prefer [RandomLeaderIdProvider] for PII-safe anonymous identity.
 *
 * ## Usage
 * ```kotlin
 * // Use with caution — hostname may be PII in multi-tenant environments
 * val provider = HostnamePidLeaderIdProvider(suffixLength = 6)
 * val leaderId = provider.nextLeaderId("my-lock") // e.g. "app-server-42:aBcDeF"
 * ```
 */
class HostnamePidLeaderIdProvider(val suffixLength: Int = 8) : LeaderIdProvider {

    init {
        suffixLength.requireGt(0, "suffixLength")
    }

    companion object : KLogging()

    override fun nextLeaderId(lockName: String): String =
        "${LeaderNodeId.Default}:${Base58.randomString(suffixLength)}"
}
