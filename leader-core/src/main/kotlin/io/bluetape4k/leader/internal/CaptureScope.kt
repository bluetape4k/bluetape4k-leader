package io.bluetape4k.leader.internal

import io.bluetape4k.leader.LeaderLockHandle

/**
 * Elector 의 `runIfLeader` 안에서 [LeaderLockHandleCapture] set/clear 를 안전하게 감싸는 헬퍼.
 *
 * ## 목적 (Step 3-P R1 mitigation)
 * 각 elector 가 [LeaderLockHandleCapture.set] 와 [LeaderLockHandleCapture.clear] 를 직접 호출하면
 * try/finally 누락 시 ThreadLocal leak 발생. 모든 elector 가 이 헬퍼를 사용하면
 * **per-backend discipline → structural enforcement**.
 *
 * AC: `grep -r "LeaderLockHandleCapture.set" leader-{core,redis,...}/src/main` → 0 match (CaptureScope.kt 외)
 *
 * ## Example
 * ```kotlin
 * // backend elector 의 runIfLeader 안에서:
 * runWithCapture(handle) {
 *     action()  // aspect 의 wrapped action — capture poll 가능
 * }
 * ```
 */
internal object CaptureScope {

    /** Sync 변형 — 동일 thread 안에서 set → action → clear. */
    inline fun <T> runWithCapture(handle: LeaderLockHandle.Real, action: () -> T): T {
        LeaderLockHandleCapture.set(handle)
        try {
            return action()
        } finally {
            LeaderLockHandleCapture.clear()
        }
    }

    /** Suspend 변형 — 동일 dispatcher 안에서 set → action → clear. */
    suspend fun <T> runWithCaptureSuspend(
        handle: LeaderLockHandle.Real,
        action: suspend () -> T,
    ): T {
        LeaderLockHandleCapture.set(handle)
        try {
            return action()
        } finally {
            LeaderLockHandleCapture.clear()
        }
    }
}
