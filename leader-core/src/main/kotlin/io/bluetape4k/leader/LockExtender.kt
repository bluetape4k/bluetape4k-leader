package io.bluetape4k.leader

import io.bluetape4k.leader.coroutines.LockHandleElement
import io.bluetape4k.leader.internal.LockStateHolder
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import java.time.Instant
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration
import kotlin.time.toKotlinDuration

/**
 * Explicitly extends the lease of the active `@LeaderElection` context.
 *
 * Single-argument variant equivalent to ShedLock's `LockExtender.extendActiveLock(Duration)` —
 * uses `lockAtMostFor` only. `lockAtLeastFor` is separated into bluetape4k's backend `minLeaseTime`
 * mechanism and requires no additional API.
 *
 * ## Behavior / Contract
 * - No active context → returns `false` + WARN log
 * - Fail-open sentinel → returns `false` + WARN log
 * - Backend extend failure → `false` (token mismatch / expired / wrong thread / transient backend error)
 * - **Absolute** lease — sets a new expiry time `lockAtMostFor` from now.
 *
 * ## Detailed result
 * Use [extendActiveLockDetailed] when operational visibility is needed — returns a sealed [ExtendOutcome].
 *
 * ## Boolean ↔ Detailed conversion contract
 * - `extendActiveLock(d): Boolean` ≡ `extendActiveLockDetailed(d).isExtended`
 * - [ExtendOutcome.Extended] → `true`
 * - [ExtendOutcome.NotHeld] / [ExtendOutcome.WrongThread] → `false` + WARN log
 * - [ExtendOutcome.BackendError] (transient) → `false` + WARN log
 *
 * ## Mismatched lockName handling
 * If `lockName` in `extendActiveLock(lockName, d)` differs from the active handle → `false` + WARN log.
 * `extendActiveLockDetailed(lockName, d)` returns [ExtendOutcome.NotHeld].
 * Call [LockAssert.assertLocked]`(lockName)` first if you want misuse detection.
 *
 * ## Group elector semantics
 * When called inside a `@LeaderGroupElection` body — extends the lease of **the currently held slot only**.
 * The `lockName` argument is the group name; direct slotId specification is not exposed.
 *
 * ## Concurrency (Watchdog × LockExtender)
 * - Both make atomic backend extend calls → race-free, but **last-write-wins**.
 * - Calling `extendActiveLock(d)` updates [io.bluetape4k.leader.internal.ExtendDelegate.lastExtendDeadline]
 *   → watchdog skips on the next tick if the user deadline is later (R2 mitigation).
 * - Disable watchdog if precise TTL protection is required (= ShedLock equivalent mode).
 *
 * ## ⚠️ Reactor non-suspend operators not supported (Step 3-P R5)
 * `.map { LockExtender.extendActiveLock(...) }` etc. are not supported —
 * use `.flatMap { mono { LockExtender.extendActiveLockSuspend(...) } }` instead.
 *
 * ## Example
 * ```kotlin
 * @LeaderElection(name = "long-job", leaseTime = 30.seconds)
 * fun runJob() {
 *     // ... 25 seconds of work ...
 *     LockExtender.extendActiveLock(60.seconds)  // TTL = now + 60s
 *     // ... additional 50 seconds of work ...
 * }
 * ```
 */
object LockExtender : KLogging() {

    /**
     * Extends the lease of the active lock scope on the current thread.
     *
     * @param lockAtMostFor the new lease duration
     * @return `true` if the extend succeeded, `false` otherwise
     */
    @JvmStatic
    fun extendActiveLock(lockAtMostFor: Duration): Boolean {
        val outcome = extendActiveLockDetailed(lockAtMostFor)
        return processBooleanResult(outcome)
    }

    /**
     * Extends the lease of the active lock scope for the given lock name on the current thread.
     *
     * @param lockName the lock name to extend
     * @param lockAtMostFor the new lease duration
     * @return `true` if the extend succeeded, `false` otherwise
     */
    @JvmStatic
    fun extendActiveLock(lockName: String, lockAtMostFor: Duration): Boolean {
        val outcome = extendActiveLockDetailed(lockName, lockAtMostFor)
        return processBooleanResult(outcome)
    }

    /**
     * [java.time.Duration] overload for Java callers.
     *
     * @param lockAtMostFor the new lease duration ([java.time.Duration])
     * @return `true` if the extend succeeded, `false` otherwise
     */
    @JvmStatic
    fun extendActiveLock(lockAtMostFor: java.time.Duration): Boolean =
        extendActiveLock(lockAtMostFor.toKotlinDuration())

    /**
     * Lock name + [java.time.Duration] overload for Java callers.
     *
     * @param lockName the lock name to extend
     * @param lockAtMostFor the new lease duration ([java.time.Duration])
     * @return `true` if the extend succeeded, `false` otherwise
     */
    @JvmStatic
    fun extendActiveLock(lockName: String, lockAtMostFor: java.time.Duration): Boolean =
        extendActiveLock(lockName, lockAtMostFor.toKotlinDuration())

    /**
     * Sync variant returning a detailed [ExtendOutcome].
     *
     * @param lockAtMostFor the new lease duration
     * @return sealed [ExtendOutcome] result
     */
    @JvmStatic
    fun extendActiveLockDetailed(lockAtMostFor: Duration): ExtendOutcome {
        val handle = LockStateHolder.peekSync()
            ?: return outsideScope()
        return extendDetailedInternal(handle, lockAtMostFor)
    }

    /**
     * Sync variant returning a detailed [ExtendOutcome] for the given lock name.
     *
     * @param lockName the lock name to extend
     * @param lockAtMostFor the new lease duration
     * @return sealed [ExtendOutcome] result
     */
    @JvmStatic
    fun extendActiveLockDetailed(lockName: String, lockAtMostFor: Duration): ExtendOutcome {
        val handle = LockStateHolder.peekSyncMatching(lockName)
            ?: return outsideScope()
        return extendDetailedInternal(handle, lockAtMostFor)
    }

    /**
     * Suspend variant — checks only `coroutineContext[LockHandleElement]`.
     *
     * @param lockAtMostFor the new lease duration
     * @return `true` if the extend succeeded, `false` otherwise
     */
    suspend fun extendActiveLockSuspend(lockAtMostFor: Duration): Boolean {
        val outcome = extendActiveLockDetailedSuspend(lockAtMostFor)
        return processBooleanResult(outcome)
    }

    /**
     * Suspend variant for the given lock name.
     *
     * @param lockName the lock name to extend
     * @param lockAtMostFor the new lease duration
     * @return `true` if the extend succeeded, `false` otherwise
     */
    suspend fun extendActiveLockSuspend(lockName: String, lockAtMostFor: Duration): Boolean {
        val outcome = extendActiveLockDetailedSuspend(lockName, lockAtMostFor)
        return processBooleanResult(outcome)
    }

    /**
     * Suspend variant returning a detailed [ExtendOutcome].
     *
     * @param lockAtMostFor the new lease duration
     * @return sealed [ExtendOutcome] result
     */
    suspend fun extendActiveLockDetailedSuspend(lockAtMostFor: Duration): ExtendOutcome {
        val handle = coroutineContext[LockHandleElement]?.handle
            ?: return outsideScope()
        return extendDetailedSuspendInternal(handle, lockAtMostFor)
    }

    /**
     * Suspend variant returning a detailed [ExtendOutcome] for the given lock name.
     *
     * @param lockName the lock name to extend
     * @param lockAtMostFor the new lease duration
     * @return sealed [ExtendOutcome] result
     */
    suspend fun extendActiveLockDetailedSuspend(lockName: String, lockAtMostFor: Duration): ExtendOutcome {
        val handle = coroutineContext[LockHandleElement]?.handle
        if (handle == null || handle.lockName != lockName) return outsideScope()
        return extendDetailedSuspendInternal(handle, lockAtMostFor)
    }

    // --- internal helpers ---

    private fun extendDetailedInternal(handle: LeaderLockHandle, lockAtMostFor: Duration): ExtendOutcome {
        if (handle is LeaderLockHandle.FailOpen) {
            log.warn { "LockExtender — current scope is fail-open sentinel (lockName=${handle.lockName})" }
            return ExtendOutcome.NotHeld
        }
        val real = handle as LeaderLockHandle.Real
        // ⚠️ Tier 8 T8-H1 — backend extend 호출 후 Extended 일 때만 lastExtendDeadline 갱신.
        //   pre-extend 갱신 시 backend 가 NotHeld/BackendError 반환해도 watchdog 가 deadline 기준 skip → split-brain.
        val outcome = real.extend(lockAtMostFor)
        if (outcome is ExtendOutcome.Extended) {
            real.extendDelegate.lastExtendDeadline.set(Instant.now().plusMillis(lockAtMostFor.inWholeMilliseconds))
        }
        return outcome
    }

    private suspend fun extendDetailedSuspendInternal(handle: LeaderLockHandle, lockAtMostFor: Duration): ExtendOutcome {
        if (handle is LeaderLockHandle.FailOpen) {
            log.warn { "LockExtender — current scope is fail-open sentinel (lockName=${handle.lockName})" }
            return ExtendOutcome.NotHeld
        }
        val real = handle as LeaderLockHandle.Real
        // ⚠️ Tier 8 T8-H1 — backend extend 호출 후 Extended 일 때만 lastExtendDeadline 갱신.
        val outcome = real.extendSuspend(lockAtMostFor)
        if (outcome is ExtendOutcome.Extended) {
            real.extendDelegate.lastExtendDeadline.set(Instant.now().plusMillis(lockAtMostFor.inWholeMilliseconds))
        }
        return outcome
    }

    private fun outsideScope(): ExtendOutcome {
        log.warn { "LockExtender called outside an active @LeaderElection scope — returning NotHeld" }
        return ExtendOutcome.NotHeld
    }

    /**
     * Boolean ↔ Detailed 변환.
     *
     * 모든 false 반환 경로에 WARN 로그 — 운영 가시성 우선. [outsideScope] 와 fail-open sentinel 경로는
     * 이미 path-specific WARN 로그를 찍은 후 [ExtendOutcome.NotHeld] 를 반환 — double log 허용 (정보 가치 우선).
     *
     * **모든 [ExtendOutcome.BackendError] 는 Boolean API 에서 `false` 반환** (transient/non-transient 무관).
     * non-transient 명시적 처리 필요 시 caller 가 [extendActiveLockDetailed]/[extendActiveLockDetailedSuspend]
     * 사용 후 [io.bluetape4k.leader.internal.BackendErrorClassifier] 로 분류 + throw 결정.
     * Boolean API 자체는 ShedLock 호환 contract — 항상 boolean 반환, throw 없음.
     */
    private fun processBooleanResult(outcome: ExtendOutcome): Boolean = when (outcome) {
        is ExtendOutcome.Extended -> true
        is ExtendOutcome.NotHeld -> {
            // backend-origin NotHeld — token mismatch / takeover / lease expired
            // (outsideScope / FailOpen path 에서 온 NotHeld 는 path-specific WARN 이미 발생 — double log)
            log.warn { "LockExtender — extend returned NotHeld (token mismatch / takeover / lease expired / scope absent)" }
            false
        }
        is ExtendOutcome.WrongThread -> {
            log.warn { "LockExtender — extend failed: WrongThread (Redisson thread-bound lock called from wrong thread)" }
            false
        }
        is ExtendOutcome.BackendError -> {
            log.warn(outcome.cause) {
                "LockExtender — backend error during extend " +
                    "(use extendActiveLockDetailed + BackendErrorClassifier for non-transient classification)"
            }
            false
        }
    }
}
