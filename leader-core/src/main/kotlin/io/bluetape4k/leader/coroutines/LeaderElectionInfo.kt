package io.bluetape4k.leader.coroutines

import io.bluetape4k.leader.identity.LeaderIdSource
import io.bluetape4k.logging.KLogging
import kotlin.coroutines.CoroutineContext

/**
 * CoroutineContext element that propagates leader election results.
 *
 * Injected by the AOP aspect when executing `@LeaderElection` / `@LeaderGroupElection` annotated methods.
 * Accessible inside suspend / `Mono` return methods via `coroutineContext[LeaderElectionInfo]`.
 *
 * @property lockName the lock name used for election
 * @property wasElected whether this node was elected as leader and the body is executing
 * @property leaderId the audit identity of the elected leader; `null` if not elected or not available
 * @property leaderIdSource the source strategy that produced [leaderId]; `null` if [leaderId] is null
 */
data class LeaderElectionInfo(
    val lockName: String,
    val wasElected: Boolean,
    val leaderId: String? = null,
    val leaderIdSource: LeaderIdSource? = null,
) : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<LeaderElectionInfo>, KLogging()
    override val key: CoroutineContext.Key<*> get() = Key
}

/**
 * Validates the pairing invariants of this [LeaderElectionInfo].
 *
 * ## Contract
 * - [LeaderElectionInfo.leaderId] and [LeaderElectionInfo.leaderIdSource] must both be null or both non-null.
 * - [LeaderElectionInfo.wasElected] true implies [LeaderElectionInfo.leaderId] is non-null (hasLeader invariant).
 *
 * @throws IllegalArgumentException if any invariant is violated
 * @return this instance (for chaining)
 */
fun LeaderElectionInfo.validate(): LeaderElectionInfo = apply {
    require((leaderId == null) == (leaderIdSource == null)) {
        "leaderId and leaderIdSource must both be null or both non-null: leaderId=$leaderId, leaderIdSource=$leaderIdSource"
    }
    require(!wasElected || leaderId != null) {
        "wasElected=true implies leaderId must be non-null: leaderId=$leaderId"
    }
}
