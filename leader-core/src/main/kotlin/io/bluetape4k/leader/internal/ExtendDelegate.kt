package io.bluetape4k.leader.internal

import io.bluetape4k.leader.ExtendOutcome
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

/**
 * SPI that exposes atomic extend of a backend lock as a single reference.
 *
 * ⚠️ **SPI for backend modules only (leader-redis-lettuce, leader-redis-redisson, leader-mongodb, etc.)**.
 * Application code must not implement this directly — use [LockAssert] / [LockExtender] instead.
 *
 * [LeaderLockHandle.Real.extendDelegate] and [LeaderLeaseAutoExtender] (Watchdog) share the same
 * instance — race-free guarantee (Step 3-P R2 mitigation).
 *
 * ## Behavior / Contract
 * - `extend` calls the backend atomic extend — implemented by sync backends (Lettuce sync, Redisson, Hazelcast, Exposed JDBC, ZK).
 * - `extendSuspend` default calls sync `extend` directly — **blocking backends must override** using
 *   `withContext(Dispatchers.IO)` + `coroutineContext.ensureActive()` (R9 mitigation).
 * - `lastExtendDeadline` tracks the expire deadline at the time user calls `LockExtender.extendActiveLock(d)` (R2 mitigation).
 *   If the Watchdog tick finds `now() + watchdogCadence < lastExtendDeadline.get()`, backend extend is skipped.
 *
 * ## R2 Watchdog skip semantics (Step 3-P)
 * User calls `extend(60s)` → `lastExtendDeadline = now + 60s` is updated.
 * Watchdog cadence = leaseTime/3 (e.g. 10s for 30s lease). On tick, if `now + 10s < deadline`, skip.
 * → Prevents user-extended lease from being silently shortened by the watchdog (split-brain guard).
 */
interface ExtendDelegate {

    fun extend(lockAtMostFor: Duration): ExtendOutcome

    /**
     * Suspend extend.
     *
     * Default implementation calls sync [extend] directly — **for non-blocking / native suspend backends only**.
     * Blocking backends (Lettuce sync, Hazelcast IMap, Exposed JDBC, Redisson, etc.) must override using
     * `withContext(Dispatchers.IO)` + `coroutineContext.ensureActive()`.
     *
     * AC-21: blocking backend [ExtendDelegate.extendSuspend] calls default 0 times (verified by source grep).
     */
    suspend fun extendSuspend(lockAtMostFor: Duration): ExtendOutcome = extend(lockAtMostFor)

    /**
     * Returns whether the current token is still alive in the backend.
     */
    fun isHeld(): Boolean

    /**
     * Tracks the expire deadline of user explicit extend calls (R2 mitigation).
     *
     * `LockExtender.extendActiveLock(d)` sets: `now() + d`.
     * Watchdog reads: if `now() + cadence < lastExtendDeadline.get()`, backend call is skipped.
     *
     * **Implementation rule**: must return the stored `AtomicReference<Instant>` instance.
     * Creating a new object on each `get()` discards `.set()` calls and invalidates R2 mitigation.
     * Example:
     * ```kotlin
     * private val _lastExtendDeadline = AtomicReference(Instant.EPOCH)
     * override val lastExtendDeadline: AtomicReference<Instant> get() = _lastExtendDeadline
     * ```
     *
     * Initial value `Instant.EPOCH` — watchdog always proceeds (no user explicit extend).
     */
    val lastExtendDeadline: AtomicReference<Instant>
}
