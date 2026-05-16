package io.bluetape4k.leader.zookeeper.internal

import io.bluetape4k.leader.internal.BackendErrorClassifier
import io.bluetape4k.leader.internal.BackendErrorKind
import org.apache.zookeeper.KeeperException

/**
 * ZooKeeper backend exception classifier — T13 PR 8 (Issue #79).
 *
 * ## Behavior / Contract
 * - [KeeperException.ConnectionLossException] → [BackendErrorKind.TRANSIENT]
 *   (transient network disconnect — retryable)
 * - [KeeperException.OperationTimeoutException] → [BackendErrorKind.TRANSIENT]
 *   (request timeout — retryable)
 * - [KeeperException.SessionExpiredException] → [BackendErrorKind.NON_TRANSIENT]
 *   (session expired — ephemeral lock automatically released, unrecoverable)
 * - [KeeperException.SessionMovedException] → [BackendErrorKind.NON_TRANSIENT]
 *   (session moved — client reconnected to a different server, lock state cannot be guaranteed)
 * - Other [KeeperException] subtypes → [BackendErrorKind.NON_TRANSIENT] (safe default)
 * - All others → `null` (unclassifiable — delegated to the next classifier in the chain)
 *
 * ## Usage
 * Registered as a chain entry in [io.bluetape4k.leader.internal.CompositeBackendErrorClassifier] by the elector.
 *
 * ## Note (R16)
 * ZooKeeper is session-based — there is no TTL concept, so the [io.bluetape4k.leader.LeaderLeaseAutoExtender]
 * watchdog is disabled. Backend error classification is therefore used only via the
 * caller-driven `LockExtender.extendActiveLock` path.
 */
internal object ZooKeeperBackendErrorClassifier: BackendErrorClassifier {

    override fun classify(cause: Throwable): BackendErrorKind? = when (cause) {
        is KeeperException.ConnectionLossException -> BackendErrorKind.TRANSIENT
        is KeeperException.OperationTimeoutException -> BackendErrorKind.TRANSIENT
        is KeeperException.SessionExpiredException -> BackendErrorKind.NON_TRANSIENT
        is KeeperException.SessionMovedException -> BackendErrorKind.NON_TRANSIENT
        is KeeperException -> BackendErrorKind.NON_TRANSIENT
        else -> null
    }
}
