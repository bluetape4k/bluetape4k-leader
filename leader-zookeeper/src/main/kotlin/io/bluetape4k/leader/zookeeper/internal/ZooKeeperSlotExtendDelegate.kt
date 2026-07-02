package io.bluetape4k.leader.zookeeper.internal

import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.internal.ExtendDelegate
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import org.apache.curator.framework.CuratorFramework
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

/**
 * [ExtendDelegate] for the per-slot [org.apache.curator.framework.recipes.locks.Lease] of the
 * ZooKeeper group elector — T13 PR 8 (Issue #79).
 *
 * ## Behavior / Contract (PASSTHROUGH — Spec §6 row 12)
 *
 * A `Lease` from [org.apache.curator.framework.recipes.locks.InterProcessSemaphoreV2] is an
 * ephemeral znode with no TTL. It is only released by `Lease.close()` or session expiry.
 *
 * - [extend] / [extendSuspend]: returns [ExtendOutcome.Extended] (observedExpireAt = [Instant.MAX]) only while
 *   the delegate is alive and the lease ephemeral znode still exists in ZooKeeper.
 * - [isHeld]: returns false after [markReleased] or when the lease node is no longer positively observable.
 *
 * `Lease` only exposes the acquired node name, so the elector passes it to this delegate for backend liveness checks.
 *
 * ## R16 Enforcement
 * Group elector always uses `autoExtend=false` (no option available) — watchdog disabled.
 * The [extend] method on this delegate is called only via the user-driven `LockExtender.extendActiveLock` path.
 */
internal class ZooKeeperSlotExtendDelegate(
    private val client: CuratorFramework,
    private val slotKey: String,
    leaseNodeName: String,
): ExtendDelegate {

    companion object: KLogging()

    private val leasePath = ZooKeeperOwnershipProbe.leaseNodePath(slotKey, leaseNodeName)
    private val released = AtomicBoolean(false)

    private val _lastExtendDeadline = AtomicReference(Instant.EPOCH)
    override val lastExtendDeadline: AtomicReference<Instant> get() = _lastExtendDeadline

    /**
     * Called by the elector just before `lease.close()` to transition the delegate to the NotHeld state.
     *
     * Race guard (synchronizes handle pop with delegate state):
     * - Even after `lease.close()`, any extend call via a user-held handle reference returns NotHeld.
     */
    fun markReleased() {
        released.set(true)
    }

    override fun extend(lockAtMostFor: Duration): ExtendOutcome = doExtend()

    override suspend fun extendSuspend(lockAtMostFor: Duration): ExtendOutcome = doExtend()

    override fun isHeld(): Boolean =
        try {
            isBackendHeld()
        } catch (e: Exception) {
            log.warn(e) { "ZooKeeper group isHeld failed. slotKey=$slotKey" }
            false
        }

    private fun doExtend(): ExtendOutcome =
        try {
            if (isBackendHeld()) ExtendOutcome.Extended(Instant.MAX)
            else ExtendOutcome.NotHeld
        } catch (e: Exception) {
            log.warn(e) { "ZooKeeper group extend (passthrough) failed. slotKey=$slotKey" }
            ExtendOutcome.BackendError(e)
        }

    private fun isBackendHeld(): Boolean =
        !released.get() && ZooKeeperOwnershipProbe.isLiveNode(client, leasePath)
}
