package io.bluetape4k.leader.zookeeper.internal

import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.internal.ExtendDelegate
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CancellationException
import org.apache.curator.framework.CuratorFramework
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

/**
 * [ExtendDelegate] for the per-slot [org.apache.curator.framework.recipes.locks.Lease] of the ZooKeeper suspend group elector
 * — T13 PR 8 (Issue #79).
 *
 * ## Behavior / Contract (PASSTHROUGH — Spec §6 row 12)
 *
 * - [extend] / [extendSuspend]: returns [ExtendOutcome.Extended] (observedExpireAt = [Instant.MAX]) only while
 *   the delegate is alive and the lease ephemeral znode still exists in ZooKeeper.
 * - [isHeld]: returns false after [markReleased] or when the lease node is no longer positively observable.
 *
 * ## R16 enforce
 * Group elector always uses `autoExtend=false` — watchdog is disabled.
 */
internal class ZooKeeperSuspendSlotExtendDelegate(
    private val client: CuratorFramework,
    private val slotKey: String,
    leaseNodeName: String,
): ExtendDelegate {

    companion object: KLoggingChannel()

    private val leasePath = ZooKeeperOwnershipProbe.leaseNodePath(slotKey, leaseNodeName)
    private val released = AtomicBoolean(false)

    private val _lastExtendDeadline = AtomicReference(Instant.EPOCH)
    override val lastExtendDeadline: AtomicReference<Instant> get() = _lastExtendDeadline

    fun markReleased() {
        released.set(true)
    }

    override fun extend(lockAtMostFor: Duration): ExtendOutcome = doExtend()

    override suspend fun extendSuspend(lockAtMostFor: Duration): ExtendOutcome =
        try {
            doExtend()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "ZooKeeperSuspend group extendSuspend (passthrough) failed. slotKey=$slotKey" }
            ExtendOutcome.BackendError(e)
        }

    override fun isHeld(): Boolean =
        try {
            isBackendHeld()
        } catch (e: Exception) {
            log.warn(e) { "ZooKeeperSuspend group isHeld failed. slotKey=$slotKey" }
            false
        }

    private fun doExtend(): ExtendOutcome =
        try {
            if (isBackendHeld()) ExtendOutcome.Extended(Instant.MAX)
            else ExtendOutcome.NotHeld
        } catch (e: Exception) {
            log.warn(e) { "ZooKeeperSuspend group extend (passthrough) failed. slotKey=$slotKey" }
            ExtendOutcome.BackendError(e)
        }

    private fun isBackendHeld(): Boolean =
        !released.get() && ZooKeeperOwnershipProbe.isLiveNode(client, leasePath)
}
