package io.bluetape4k.leader

import io.bluetape4k.leader.internal.ExtendDelegate
import io.bluetape4k.leader.internal.SuspendExtendDelegate
import io.bluetape4k.support.requireGe
import java.io.Serializable
import kotlin.time.Duration

/**
 * Handle for an active lock. Pushed by the AOP aspect and read by `LockAssert` / `LockExtender`.
 *
 * ## Variants
 * - [Real] — holds a real backend lock; `extend()` / `extendSuspend()` can be called.
 * - [FailOpen] — fail-open sentinel; extend always returns [ExtendOutcome.NotHeld] per external definition.
 *
 * ## Behavior / Contract
 * - External construction is blocked **at source API level** via `internal constructor`. Only AOP aspects or electors create instances.
 *   ⚠️ `internal` is not a reflection/security boundary — it means source API restriction to trusted callers only.
 * - `when` branches on the sealed class are exhaustive — no `else` branch.
 *
 * ## Example
 * ```kotlin
 * when (val handle = LockStateHolder.peekSync()) {
 *     is LeaderLockHandle.Real -> handle.extend(60.seconds)
 *     is LeaderLockHandle.FailOpen -> error("fail-open scope — no real lock")
 *     null -> error("no active scope")
 * }
 * ```
 */
sealed class LeaderLockHandle : Serializable {

    abstract val identity: LockIdentity
    val lockName: String get() = identity.lockName
    abstract val reentryDepth: Int
    val isReentrant: Boolean get() = reentryDepth > 0

    /**
     * Compares whether another [LockIdentity] refers to the same lock (for reentrant peek).
     *
     * Because [LockIdentity.equals] excludes `factoryBeanName`, the same lock with different
     * sync/suspend factories still passes reentrant pass-through (R3 mitigation).
     */
    fun matchesIdentity(other: LockIdentity): Boolean = identity == other

    /**
     * Active backend lock handle.
     *
     * @property identity full lock identity (unit of reentrant peek comparison)
     * @property token Base58(8) lock token — backend atomic extend guard
     * @property acquiredAtNanos `System.nanoTime()` acquisition timestamp — for minLeaseTime calculation
     * @property slotId group lock permit/slot identifier; `null` for single-leader
     * @property acquiringThreadId Redisson thread-bound extend validation; `null` for non-Redisson.
     *   **Do not use for ownership comparison** (R6-P2) — Redisson cross-thread debug info only.
     * @property reentryDepth 0 = outermost real lock, >0 = reentrant passthrough copy
     * @property extendDelegate shares same reference as watchdog (AC-15)
     * @property auditLeaderId Audit identity of the elected leader stamped at acquisition time.
     *   Excluded from equals/hashCode — traceability only.
     */
    class Real internal constructor(
        override val identity: LockIdentity,
        val token: String,
        val acquiredAtNanos: Long,
        val slotId: String? = null,
        val acquiringThreadId: Long? = null,
        override val reentryDepth: Int = 0,
        /** ⚠️ Backend module / aspect SPI only — do not access from application code. */
        val extendDelegate: ExtendDelegate,
        val auditLeaderId: String? = null,
    ) : LeaderLockHandle() {

        /**
         * Invokes backend atomic extend.
         *
         * Even for a reentrant passthrough copy, the outer [extendDelegate] is retained as-is,
         * so **calling from an inner scope renews the outer/backend lease** (R5-F3 / SF11).
         */
        fun extend(lockAtMostFor: Duration): ExtendOutcome = extendDelegate.extend(lockAtMostFor)

        /** Suspend variant — non-blocking if the backend is suspend-native, otherwise overridden with `withContext(IO)`. */
        suspend fun extendSuspend(lockAtMostFor: Duration): ExtendOutcome =
            extendDelegate.extendSuspend(lockAtMostFor)

        /**
         * Checks whether the current token is still alive in the backend. Returns `false` if a takeover occurs after lease expiry.
         */
        fun isStillHeld(): Boolean = extendDelegate.isHeld()

        /**
         * Suspend ownership check. Uses the coroutine-native path when the backend delegate supports it.
         */
        suspend fun isStillHeldSuspend(): Boolean =
            if (extendDelegate is SuspendExtendDelegate) extendDelegate.isHeldSuspend() else extendDelegate.isHeld()

        /**
         * Creates a reentrant passthrough copy.
         *
         * **Inner extend calls the outer [extendDelegate] as-is → outer/backend lease is renewed** (R5-F3 / SF11).
         */
        internal fun withReentryDepth(n: Int): Real {
            n.requireGe(0, "n")
            return Real(identity, token, acquiredAtNanos, slotId, acquiringThreadId, n, extendDelegate, auditLeaderId)
        }

        // equals/hashCode 는 (identity, token, reentryDepth, slotId) 기반.
        // acquiringThreadId 는 ownership 비교에 사용 금지 (R6-P2).
        // extendDelegate 는 reference 비교 무의미 — 제외.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Real) return false
            return identity == other.identity &&
                token == other.token &&
                reentryDepth == other.reentryDepth &&
                slotId == other.slotId
        }

        override fun hashCode(): Int {
            var result = identity.hashCode()
            result = 31 * result + token.hashCode()
            result = 31 * result + reentryDepth
            result = 31 * result + (slotId?.hashCode() ?: 0)
            return result
        }

        override fun toString(): String = buildString {
            append("LeaderLockHandle.Real(identity=$identity, token='$token', reentryDepth=$reentryDepth, slotId=$slotId")
            if (auditLeaderId != null) append(", auditLeaderId='$auditLeaderId'")
            append(")")
        }

        companion object {
            private const val serialVersionUID = 1L
        }
    }

    /**
     * Fail-open sentinel — runs the body without holding a backend lock when `failureMode = FAIL_OPEN_RUN`.
     *
     * `LockAssert.assertLocked()` throws — no lock is held inside a fail-open scope.
     * `LockExtender.extendActiveLock(d)` returns `false` and emits a WARN log.
     */
    class FailOpen internal constructor(
        override val identity: LockIdentity,
    ) : LeaderLockHandle() {
        override val reentryDepth: Int = 0

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is FailOpen) return false
            return identity == other.identity
        }

        override fun hashCode(): Int = identity.hashCode()

        override fun toString(): String = "LeaderLockHandle.FailOpen(identity=$identity)"

        companion object {
            private const val serialVersionUID = 1L
        }
    }

    companion object {
        private const val serialVersionUID = 1L

        /**
         * Factory for backend modules only. Called by AOP aspects / electors.
         *
         * ⚠️ Do not call directly from application code — external callers should use `LockAssert` / `LockExtender` only.
         * This factory is used by backend modules such as `leader-redis-lettuce`, `leader-redis-redisson`,
         * `leader-mongodb`, `leader-exposed-jdbc`, `leader-exposed-r2dbc`, `leader-hazelcast`, `leader-zookeeper`.
         */
        fun real(
            identity: LockIdentity,
            token: String,
            acquiredAtNanos: Long,
            slotId: String? = null,
            acquiringThreadId: Long? = null,
            reentryDepth: Int = 0,
            extendDelegate: ExtendDelegate,
            auditLeaderId: String? = null,  // END positional — default null for backward compat
        ): Real = Real(identity, token, acquiredAtNanos, slotId, acquiringThreadId, reentryDepth, extendDelegate, auditLeaderId)

        /**
         * Sentinel factory for backend modules / aspects only. Used when `failureMode = FAIL_OPEN_RUN`.
         *
         * ⚠️ Do not call directly from application code.
         */
        fun failOpen(identity: LockIdentity): FailOpen = FailOpen(identity)
    }
}
