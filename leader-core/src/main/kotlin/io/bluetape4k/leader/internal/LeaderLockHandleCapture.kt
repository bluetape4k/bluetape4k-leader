package io.bluetape4k.leader.internal

import io.bluetape4k.leader.LeaderLockHandle

/**
 * Elector → Aspect 간 handle 전달용 ThreadLocal.
 *
 * ## 엄격한 invariant (Step 2-R R10)
 * - Elector 는 acquire 후 action 호출 **직전** 동일 thread 에서 [set] 호출.
 * - Aspect 는 action lambda 의 **첫 statement** 로 [poll] 호출.
 * - **capture 누락 (poll 결과 null) → `CaptureInvariantException` throw** ("elector did not capture handle — bug")
 *   silent fallback to FailOpen 절대 금지 (Codex F10 / SF3).
 * - virtual thread / dispatcher hop 사이에 set/poll 분리 금지.
 *
 * ## 사용 권고
 * 직접 호출하지 말고 [CaptureScope.runWithCapture] 또는 [CaptureScope.runWithCaptureSuspend] 사용
 * — per-backend discipline → structural enforcement (Step 3-P R1 mitigation).
 */
internal object LeaderLockHandleCapture {

    private val tl: ThreadLocal<LeaderLockHandle.Real?> = ThreadLocal()

    fun set(handle: LeaderLockHandle.Real) {
        tl.set(handle)
    }

    /** ThreadLocal 에서 값을 꺼내고 즉시 clear. */
    fun poll(): LeaderLockHandle.Real? {
        val handle = tl.get()
        tl.remove()
        return handle
    }

    fun clear() {
        tl.remove()
    }
}
