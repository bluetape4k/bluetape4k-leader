package io.bluetape4k.leader.zookeeper.internal

import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.internal.ExtendDelegate
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
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
 * - [extend] / [extendSuspend]: while the delegate is alive (i.e., the handle is pushed into scope),
 *   returns [ExtendOutcome.Extended] (observedExpireAt = [Instant.MAX]). After the elector calls
 *   [markReleased] in `finally`, returns [ExtendOutcome.NotHeld] (defensive — guards against races
 *   where extend is called after handle pop).
 * - [isHeld]: delegates directly to the [released] state. `true` = not yet released.
 *
 * `Lease` has no liveness-query API, so the elector explicitly signals state transition via [markReleased].
 *
 * ## R16 Enforcement
 * Group elector always uses `autoExtend=false` (no option available) — watchdog disabled.
 * The [extend] method on this delegate is called only via the user-driven `LockExtender.extendActiveLock` path.
 */
internal class ZooKeeperSlotExtendDelegate(
    private val slotKey: String,
): ExtendDelegate {

    companion object: KLogging()

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

    override fun isHeld(): Boolean = !released.get()

    private fun doExtend(): ExtendOutcome =
        try {
            if (released.get()) ExtendOutcome.NotHeld
            else ExtendOutcome.Extended(Instant.MAX)
        } catch (e: Exception) {
            log.warn(e) { "ZooKeeper group extend (passthrough) failed. slotKey=$slotKey" }
            ExtendOutcome.BackendError(e)
        }
}
