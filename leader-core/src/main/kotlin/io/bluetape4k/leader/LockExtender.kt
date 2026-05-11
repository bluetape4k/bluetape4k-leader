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
 * 활성 `@LeaderElection` 컨텍스트의 lease 를 명시적으로 연장합니다.
 *
 * ShedLock 의 `LockExtender.extendActiveLock(Duration)` 에 해당하는 단일 인자 변형 —
 * `lockAtMostFor` 만 사용. `lockAtLeastFor` 는 bluetape4k 의 backend `minLeaseTime`
 * 메커니즘으로 분리되어 별도 API 불필요.
 *
 * ## 동작/계약
 * - 활성 컨텍스트 없음 → `false` 반환 + WARN log
 * - fail-open sentinel → `false` 반환 + WARN log
 * - backend extend 실패 → `false` (token mismatch / expired / wrong thread / transient backend error)
 * - **absolute** lease — `lockAtMostFor` 만큼 새 expire time 설정.
 *
 * ## Detailed result
 * 운영 가시성이 필요하면 [extendActiveLockDetailed] 사용 — [ExtendOutcome] sealed result 반환.
 *
 * ## Boolean ↔ Detailed 변환 contract
 * - `extendActiveLock(d): Boolean` ≡ `extendActiveLockDetailed(d).isExtended`
 * - [ExtendOutcome.Extended] → `true`
 * - [ExtendOutcome.NotHeld] / [ExtendOutcome.WrongThread] → `false` + WARN log
 * - [ExtendOutcome.BackendError] (transient) → `false` + WARN log
 *
 * ## mismatched lockName 처리
 * `extendActiveLock(lockName, d)` 의 `lockName` 이 현재 active handle 과 다르면 → `false` + WARN log.
 * `extendActiveLockDetailed(lockName, d)` 는 [ExtendOutcome.NotHeld] 반환.
 * 오용 탐지를 원하면 [LockAssert.assertLocked]`(lockName)` 먼저 호출 권장.
 *
 * ## Group elector 의미
 * `@LeaderGroupElection` 본문 안에서 호출 시 — **현재 보유한 slot 만** 의 lease 를 연장.
 * `lockName` 인자는 group name. slotId 직접 지정 API 미노출.
 *
 * ## 동시성 (Watchdog × LockExtender)
 * - 둘 다 atomic backend extend 호출 → race-free, 단 **last-write-wins**.
 * - `extendActiveLock(d)` 호출 시 [io.bluetape4k.leader.internal.ExtendDelegate.lastExtendDeadline] 갱신
 *   → watchdog 가 다음 tick 에서 user deadline 이 크면 skip (R2 mitigation).
 * - 정확한 TTL 보호가 필요하면 watchdog OFF 권장 (= ShedLock 등가 모드).
 *
 * ## ⚠️ Reactor non-suspend operator 미지원 (Step 3-P R5)
 * `.map { LockExtender.extendActiveLock(...) }` 등 미지원 —
 * `.flatMap { mono { LockExtender.extendActiveLockSuspend(...) } }` 사용.
 *
 * ## Example
 * ```kotlin
 * @LeaderElection(name = "long-job", leaseTime = 30.seconds)
 * fun runJob() {
 *     // ... 25초 작업 ...
 *     LockExtender.extendActiveLock(60.seconds)  // TTL = now + 60s
 *     // ... 추가 50초 작업 ...
 * }
 * ```
 */
object LockExtender : KLogging() {

    /**
     * 현재 스레드의 활성 lock scope 의 lease 를 연장합니다.
     *
     * @param lockAtMostFor 새 lease 기간
     * @return extend 성공이면 `true`, 그 외 `false`
     */
    @JvmStatic
    fun extendActiveLock(lockAtMostFor: Duration): Boolean {
        val outcome = extendActiveLockDetailed(lockAtMostFor)
        return processBooleanResult(outcome)
    }

    /**
     * 특정 lock 이름으로 현재 스레드의 활성 lock scope 의 lease 를 연장합니다.
     *
     * @param lockName 연장할 lock 이름
     * @param lockAtMostFor 새 lease 기간
     * @return extend 성공이면 `true`, 그 외 `false`
     */
    @JvmStatic
    fun extendActiveLock(lockName: String, lockAtMostFor: Duration): Boolean {
        val outcome = extendActiveLockDetailed(lockName, lockAtMostFor)
        return processBooleanResult(outcome)
    }

    /**
     * Java 사용자용 [java.time.Duration] overload.
     *
     * @param lockAtMostFor 새 lease 기간 ([java.time.Duration])
     * @return extend 성공이면 `true`, 그 외 `false`
     */
    @JvmStatic
    fun extendActiveLock(lockAtMostFor: java.time.Duration): Boolean =
        extendActiveLock(lockAtMostFor.toKotlinDuration())

    /**
     * 특정 lock 이름 + Java 사용자용 [java.time.Duration] overload.
     *
     * @param lockName 연장할 lock 이름
     * @param lockAtMostFor 새 lease 기간 ([java.time.Duration])
     * @return extend 성공이면 `true`, 그 외 `false`
     */
    @JvmStatic
    fun extendActiveLock(lockName: String, lockAtMostFor: java.time.Duration): Boolean =
        extendActiveLock(lockName, lockAtMostFor.toKotlinDuration())

    /**
     * 상세 [ExtendOutcome] 를 반환하는 sync 변형.
     *
     * @param lockAtMostFor 새 lease 기간
     * @return [ExtendOutcome] sealed 결과
     */
    @JvmStatic
    fun extendActiveLockDetailed(lockAtMostFor: Duration): ExtendOutcome {
        val handle = LockStateHolder.peekSync()
            ?: return outsideScope()
        return extendDetailedInternal(handle, lockAtMostFor)
    }

    /**
     * 특정 lock 이름으로 상세 [ExtendOutcome] 를 반환하는 sync 변형.
     *
     * @param lockName 연장할 lock 이름
     * @param lockAtMostFor 새 lease 기간
     * @return [ExtendOutcome] sealed 결과
     */
    @JvmStatic
    fun extendActiveLockDetailed(lockName: String, lockAtMostFor: Duration): ExtendOutcome {
        val handle = LockStateHolder.peekSyncMatching(lockName)
            ?: return outsideScope()
        return extendDetailedInternal(handle, lockAtMostFor)
    }

    /**
     * Suspend 변형 — `coroutineContext[LockHandleElement]` 만 검사.
     *
     * @param lockAtMostFor 새 lease 기간
     * @return extend 성공이면 `true`, 그 외 `false`
     */
    suspend fun extendActiveLockSuspend(lockAtMostFor: Duration): Boolean {
        val outcome = extendActiveLockDetailedSuspend(lockAtMostFor)
        return processBooleanResult(outcome)
    }

    /**
     * 특정 lock 이름으로 Suspend 변형.
     *
     * @param lockName 연장할 lock 이름
     * @param lockAtMostFor 새 lease 기간
     * @return extend 성공이면 `true`, 그 외 `false`
     */
    suspend fun extendActiveLockSuspend(lockName: String, lockAtMostFor: Duration): Boolean {
        val outcome = extendActiveLockDetailedSuspend(lockName, lockAtMostFor)
        return processBooleanResult(outcome)
    }

    /**
     * 상세 [ExtendOutcome] 를 반환하는 suspend 변형.
     *
     * @param lockAtMostFor 새 lease 기간
     * @return [ExtendOutcome] sealed 결과
     */
    suspend fun extendActiveLockDetailedSuspend(lockAtMostFor: Duration): ExtendOutcome {
        val handle = coroutineContext[LockHandleElement]?.handle
            ?: return outsideScope()
        return extendDetailedSuspendInternal(handle, lockAtMostFor)
    }

    /**
     * 특정 lock 이름으로 상세 [ExtendOutcome] 를 반환하는 suspend 변형.
     *
     * @param lockName 연장할 lock 이름
     * @param lockAtMostFor 새 lease 기간
     * @return [ExtendOutcome] sealed 결과
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
        // R2 mitigation — lastExtendDeadline 갱신으로 watchdog skip 유도
        real.extendDelegate.lastExtendDeadline.set(Instant.now().plusMillis(lockAtMostFor.inWholeMilliseconds))
        return real.extend(lockAtMostFor)
    }

    private suspend fun extendDetailedSuspendInternal(handle: LeaderLockHandle, lockAtMostFor: Duration): ExtendOutcome {
        if (handle is LeaderLockHandle.FailOpen) {
            log.warn { "LockExtender — current scope is fail-open sentinel (lockName=${handle.lockName})" }
            return ExtendOutcome.NotHeld
        }
        val real = handle as LeaderLockHandle.Real
        // R2 mitigation — lastExtendDeadline 갱신으로 watchdog skip 유도
        real.extendDelegate.lastExtendDeadline.set(Instant.now().plusMillis(lockAtMostFor.inWholeMilliseconds))
        return real.extendSuspend(lockAtMostFor)
    }

    private fun outsideScope(): ExtendOutcome {
        log.warn { "LockExtender called outside an active @LeaderElection scope — returning NotHeld" }
        return ExtendOutcome.NotHeld
    }

    private fun processBooleanResult(outcome: ExtendOutcome): Boolean = when (outcome) {
        is ExtendOutcome.Extended -> true
        is ExtendOutcome.NotHeld -> {
            // WARN already logged by outsideScope() or FailOpen path; avoid double-log for those paths.
            // For NotHeld returned directly from backend (token mismatch / takeover), log here.
            false
        }
        is ExtendOutcome.WrongThread -> {
            log.warn { "LockExtender — extend failed: WrongThread (Redisson thread-bound lock called from wrong thread)" }
            false
        }
        is ExtendOutcome.BackendError -> {
            log.warn(outcome.cause) { "LockExtender — backend error during extend (transient assumed; non-transient should be classified upstream)" }
            false
        }
    }
}
