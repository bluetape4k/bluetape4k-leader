package io.bluetape4k.leader.zookeeper.internal

import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.internal.ExtendDelegate
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CancellationException
import org.apache.curator.framework.recipes.locks.InterProcessMutex
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

/**
 * [ExtendDelegate] for ZooKeeper [InterProcessMutex] (suspend) — T13 PR 8 (Issue #79).
 *
 * ## Behavior / Contract (PASSTHROUGH — Spec §6 row 12)
 *
 * ZooKeeper uses **session-based locks** with no TTL concept. `extend(d)` means
 * **session-held liveness check**, not lease extension (R3-F11).
 *
 * - [extend] / [extendSuspend]: checks `mutex.isAcquiredInThisProcess()` (Curator local counter — non-blocking)
 *   and returns [ExtendOutcome.Extended] (observedExpireAt = [Instant.MAX]) or [ExtendOutcome.NotHeld] accordingly.
 * - [extendSuspend] checks a local counter, so `withContext(IO)` wrapping is unnecessary — only re-propagates CancellationException.
 * - [isHeld]: delegates directly to `isAcquiredInThisProcess`.
 *
 * Token-based lock (session-bound) — no thread affinity.
 *
 * ## R16 enforce
 * The elector forces `enabled=false` when calling [io.bluetape4k.leader.LeaderLeaseAutoExtender.start].
 */
internal class ZooKeeperSuspendLockExtendDelegate(
    private val mutex: InterProcessMutex,
    private val lockKey: String,
): ExtendDelegate {

    companion object: KLoggingChannel()

    private val _lastExtendDeadline = AtomicReference(Instant.EPOCH)
    override val lastExtendDeadline: AtomicReference<Instant> get() = _lastExtendDeadline

    override fun extend(lockAtMostFor: Duration): ExtendOutcome = doExtend()

    override suspend fun extendSuspend(lockAtMostFor: Duration): ExtendOutcome =
        try {
            doExtend()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "ZooKeeperSuspend extendSuspend (passthrough) failed. lockKey=$lockKey" }
            ExtendOutcome.BackendError(e)
        }

    override fun isHeld(): Boolean =
        try {
            mutex.isAcquiredInThisProcess
        } catch (e: Exception) {
            log.warn(e) { "ZooKeeperSuspend isHeld failed. lockKey=$lockKey" }
            false
        }

    private fun doExtend(): ExtendOutcome =
        try {
            if (mutex.isAcquiredInThisProcess) ExtendOutcome.Extended(Instant.MAX)
            else ExtendOutcome.NotHeld
        } catch (e: Exception) {
            log.warn(e) { "ZooKeeperSuspend extend (passthrough) failed. lockKey=$lockKey" }
            ExtendOutcome.BackendError(e)
        }
}
