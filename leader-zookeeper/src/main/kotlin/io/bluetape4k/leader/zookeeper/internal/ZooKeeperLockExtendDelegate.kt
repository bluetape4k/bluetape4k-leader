package io.bluetape4k.leader.zookeeper.internal

import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.internal.ExtendDelegate
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import org.apache.curator.framework.recipes.locks.InterProcessMutex
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

/**
 * [ExtendDelegate] for ZooKeeper [InterProcessMutex] (synchronous, Curator) — T13 PR 8 (Issue #79).
 *
 * ## Behavior / Contract (PASSTHROUGH — Spec §6 row 12)
 *
 * ZooKeeper uses a **session-based lock** with no TTL concept. [InterProcessMutex] is implemented
 * as an ephemeral znode and is valid only while the ZK session is alive. Therefore `extend(d)` means
 * **session-held liveness check** rather than lease renewal (R3-F11).
 *
 * - [extend]: returns [ExtendOutcome.Extended] (observedExpireAt = [Instant.MAX]) if
 *   `mutex.isAcquiredInThisProcess()` is `true`; returns [ExtendOutcome.NotHeld] if `false`.
 *   Backend exceptions are wrapped as [ExtendOutcome.BackendError].
 * - [extendSuspend]: `isAcquiredInThisProcess()` is a local counter check — non-blocking, no
 *   `withContext(IO)` needed. Only adds `CancellationException` re-propagation.
 * - [isHeld]: delegates directly to `mutex.isAcquiredInThisProcess()`.
 * - [lastExtendDeadline]: single `AtomicReference(Instant.EPOCH)` instance (R2 mitigation interface
 *   compatibility — cosmetic in ZK since the watchdog is disabled, but [LockExtender.extendActiveLockDetailed]
 *   calls `set` on it).
 *
 * Token-based lock (session-bound) — no thread affinity (no WrongThread usage unlike Redisson).
 *
 * ## R16 Enforcement
 * The elector forces `enabled=false` when calling [io.bluetape4k.leader.LeaderLeaseAutoExtender.start].
 * The [extend] method on this delegate is called only via the user-driven `LockExtender.extendActiveLock` path.
 */
internal class ZooKeeperLockExtendDelegate(
    private val mutex: InterProcessMutex,
    private val lockKey: String,
): ExtendDelegate {

    companion object: KLogging()

    private val _lastExtendDeadline = AtomicReference(Instant.EPOCH)
    override val lastExtendDeadline: AtomicReference<Instant> get() = _lastExtendDeadline

    override fun extend(lockAtMostFor: Duration): ExtendOutcome = doExtend()

    /**
     * `isAcquiredInThisProcess()` is a Curator local counter check — non-blocking. No `withContext(IO)` needed.
     */
    override suspend fun extendSuspend(lockAtMostFor: Duration): ExtendOutcome = doExtend()

    override fun isHeld(): Boolean =
        try {
            mutex.isAcquiredInThisProcess
        } catch (e: Exception) {
            log.warn(e) { "ZooKeeper isHeld failed. lockKey=$lockKey" }
            false
        }

    private fun doExtend(): ExtendOutcome =
        try {
            if (mutex.isAcquiredInThisProcess) ExtendOutcome.Extended(Instant.MAX)
            else ExtendOutcome.NotHeld
        } catch (e: Exception) {
            log.warn(e) { "ZooKeeper extend (passthrough) failed. lockKey=$lockKey" }
            ExtendOutcome.BackendError(e)
        }
}
