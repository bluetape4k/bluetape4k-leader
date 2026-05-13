package io.bluetape4k.leader.identity

import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireNotBlank

/**
 * [LeaderIdProvider] that prepends a fixed prefix to another provider's output.
 *
 * Useful for multi-tenancy scenarios where a tenant tag distinguishes leader identities
 * across shared lock namespaces. See follow-up #42 for full multi-tenancy support.
 *
 * ## Contract
 * - [prefix] must be non-blank.
 * - Result format: `"$prefix$separator${delegate.nextLeaderId(lockName)}"`
 *
 * ## Usage
 * ```kotlin
 * val tenantProvider = CompositeLeaderIdProvider(
 *     prefix = "tenant-acme",
 *     separator = ":",
 *     delegate = RandomLeaderIdProvider.Default,
 * )
 * val leaderId = tenantProvider.nextLeaderId("billing-job") // "tenant-acme:aBcDeFgHiJkL"
 * ```
 */
class CompositeLeaderIdProvider(
    val prefix: String,
    val separator: String = ":",
    val delegate: LeaderIdProvider = RandomLeaderIdProvider.Default,
) : LeaderIdProvider {

    init {
        prefix.requireNotBlank("prefix")
    }

    companion object : KLogging()

    override fun nextLeaderId(lockName: String): String =
        "$prefix$separator${delegate.nextLeaderId(lockName)}"
}
