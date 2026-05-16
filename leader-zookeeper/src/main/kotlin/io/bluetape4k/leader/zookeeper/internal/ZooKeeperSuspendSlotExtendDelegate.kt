package io.bluetape4k.leader.zookeeper.internal

import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.internal.ExtendDelegate
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CancellationException
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
 * - [extend] / [extendSuspend]: returns [ExtendOutcome.Extended] (observedExpireAt = [Instant.MAX]) while the delegate is alive.
 *   Returns [ExtendOutcome.NotHeld] after the elector calls [markReleased] in `finally`.
 * - [extendSuspend] performs a local atomic check — `withContext(IO)` is not needed.
 * - [isHeld]: delegates directly to the [released] state.
 *
 * ## R16 enforce
 * Group elector always uses `autoExtend=false` — watchdog is disabled.
 */
internal class ZooKeeperSuspendSlotExtendDelegate(
    private val slotKey: String,
): ExtendDelegate {

    companion object: KLoggingChannel()

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

    override fun isHeld(): Boolean = !released.get()

    private fun doExtend(): ExtendOutcome =
        try {
            if (released.get()) ExtendOutcome.NotHeld
            else ExtendOutcome.Extended(Instant.MAX)
        } catch (e: Exception) {
            log.warn(e) { "ZooKeeperSuspend group extend (passthrough) failed. slotKey=$slotKey" }
            ExtendOutcome.BackendError(e)
        }
}
