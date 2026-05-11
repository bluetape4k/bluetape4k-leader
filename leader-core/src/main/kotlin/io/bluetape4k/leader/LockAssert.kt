package io.bluetape4k.leader

import io.bluetape4k.leader.coroutines.LockHandleElement
import io.bluetape4k.leader.internal.LockStateHolder
import io.bluetape4k.logging.KLogging
import kotlin.coroutines.coroutineContext

/**
 * 현재 컨텍스트가 활성 `@LeaderElection` / `@LeaderGroupElection` 본문 안에서 실행 중인지 단언합니다.
 *
 * ShedLock 의 `LockAssert.assertLocked()` 와 동일한 사용감을 제공합니다.
 *
 * ## 동작/계약
 * - 활성 lock state 없음 → [IllegalStateException]
 * - fail-open sentinel scope (`failureMode = FAIL_OPEN_RUN` 이고 backend 실패) → [IllegalStateException]
 *   (fail-open 본문은 락이 없는 상태이므로)
 * - reentrant 진입 시에도 outer 가 sentinel 이 아니면 통과
 * - sealed [LeaderLockHandle] 의 `is FailOpen` 분기는 컴파일러 exhaustive check 강제
 *
 * ## Cross-context 동작 (suspend → sync API 호출)
 * **suspend 컨텍스트에서는 [assertLockedSuspend] 호출**. sync [assertLocked] 를 suspend 안에서 호출 시
 * carrier thread 의 ThreadLocal 만 검사 → 잘못된 handle 노출 위험 (R7).
 *
 * ## ⚠️ Reactor non-suspend operator 미지원 (Step 3-P R5)
 * `.map { LockAssert.assertLocked() }`, `.filter { ... }` 등 non-suspend Reactor operator 안에서는
 * ThreadLocal / `CoroutineContext` 둘 다 없음 → throw.
 * **`.flatMap { mono { LockAssert.assertLockedSuspend() } }` 사용 권장**.
 *
 * ## Example
 * ```kotlin
 * @LeaderElection(name = "report-job")
 * fun runReport() {
 *     LockAssert.assertLocked()       // passes
 *     // ... critical work ...
 * }
 *
 * fun runOutsideAnnotation() {
 *     LockAssert.assertLocked()       // throws IllegalStateException
 * }
 * ```
 */
object LockAssert : KLogging() {

    /**
     * 현재 스레드에 활성 lock scope 가 있고 fail-open 이 아닌지 단언합니다.
     *
     * @throws IllegalStateException 활성 scope 없음 또는 fail-open sentinel 인 경우
     */
    @JvmStatic
    fun assertLocked() {
        val handle = LockStateHolder.peekSync()
            ?: error("LockAssert.assertLocked() called outside an active @LeaderElection / @LeaderGroupElection scope")
        check(handle !is LeaderLockHandle.FailOpen) {
            "LockAssert.assertLocked() — current scope is fail-open (no real lock held). lockName=${handle.lockName}"
        }
    }

    /**
     * 특정 lock 이름으로 활성 lock scope 가 있고 fail-open 이 아닌지 단언합니다.
     *
     * @param lockName 확인할 lock 이름
     * @throws IllegalStateException 해당 이름의 활성 scope 없음 또는 fail-open sentinel 인 경우
     */
    @JvmStatic
    fun assertLocked(lockName: String) {
        val handle = LockStateHolder.peekSyncMatching(lockName)
            ?: error("LockAssert.assertLocked('$lockName') — no active scope with this lock")
        check(handle !is LeaderLockHandle.FailOpen) {
            "LockAssert.assertLocked('$lockName') — current scope is fail-open"
        }
    }

    /**
     * throw 없이 현재 스레드에 실제 lock 보유 여부를 반환합니다.
     *
     * @return 활성 [LeaderLockHandle.Real] scope 있으면 `true`, 없거나 fail-open 이면 `false`
     */
    @JvmStatic
    fun isLocked(): Boolean {
        val handle = LockStateHolder.peekSync() ?: return false
        return handle is LeaderLockHandle.Real
    }

    /**
     * throw 없이 특정 lock 이름으로 실제 lock 보유 여부를 반환합니다.
     *
     * @param lockName 확인할 lock 이름
     * @return 해당 이름의 활성 [LeaderLockHandle.Real] scope 있으면 `true`, 없거나 fail-open 이면 `false`
     */
    @JvmStatic
    fun isLocked(lockName: String): Boolean {
        val handle = LockStateHolder.peekSyncMatching(lockName) ?: return false
        return handle is LeaderLockHandle.Real
    }

    /**
     * Suspend 변형 — `coroutineContext[LockHandleElement]` 만 검사.
     *
     * **ThreadLocal fallback 제거 (R7 / Codex F13)** — sync ThreadLocal 누수 차단.
     * sync `assertLocked()` 를 suspend 에서 호출 시 carrier thread 의 무관한 lock 노출 위험.
     *
     * @throws IllegalStateException 활성 scope 없음 또는 fail-open sentinel 인 경우
     */
    suspend fun assertLockedSuspend() {
        val handle = coroutineContext[LockHandleElement]?.handle
            ?: error("LockAssert.assertLockedSuspend() called outside an active @LeaderElection scope")
        check(handle !is LeaderLockHandle.FailOpen) {
            "LockAssert.assertLockedSuspend() — current scope is fail-open. lockName=${handle.lockName}"
        }
    }

    /**
     * Suspend 변형 — 특정 lock 이름으로 `coroutineContext[LockHandleElement]` 검사.
     *
     * @param lockName 확인할 lock 이름
     * @throws IllegalStateException 활성 scope 없음, lock 이름 불일치, 또는 fail-open sentinel 인 경우
     */
    suspend fun assertLockedSuspend(lockName: String) {
        val handle = coroutineContext[LockHandleElement]?.handle
            ?: error("LockAssert.assertLockedSuspend('$lockName') — no active scope")
        check(handle.lockName == lockName) {
            "LockAssert.assertLockedSuspend('$lockName') — active lock is '${handle.lockName}'"
        }
        check(handle !is LeaderLockHandle.FailOpen) {
            "LockAssert.assertLockedSuspend('$lockName') — current scope is fail-open"
        }
    }

    /**
     * throw 없이 현재 coroutine context 에서 실제 lock 보유 여부를 반환합니다.
     *
     * @return 활성 [LeaderLockHandle.Real] 이 coroutineContext 에 있으면 `true`, 없거나 fail-open 이면 `false`
     */
    suspend fun isLockedSuspend(): Boolean {
        val handle = coroutineContext[LockHandleElement]?.handle ?: return false
        return handle is LeaderLockHandle.Real
    }

    /**
     * throw 없이 특정 lock 이름으로 coroutine context 에서 실제 lock 보유 여부를 반환합니다.
     *
     * @param lockName 확인할 lock 이름
     * @return 해당 이름의 활성 [LeaderLockHandle.Real] 이 coroutineContext 에 있으면 `true`, 없거나 fail-open 이면 `false`
     */
    suspend fun isLockedSuspend(lockName: String): Boolean {
        val handle = coroutineContext[LockHandleElement]?.handle ?: return false
        return handle is LeaderLockHandle.Real && handle.lockName == lockName
    }
}
