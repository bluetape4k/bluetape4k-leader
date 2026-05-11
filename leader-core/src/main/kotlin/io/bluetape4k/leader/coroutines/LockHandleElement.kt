package io.bluetape4k.leader.coroutines

import io.bluetape4k.leader.LeaderLockHandle
import kotlin.coroutines.CoroutineContext

/**
 * Suspend / Mono 컨텍스트의 active lock handle 을 전달하는 `CoroutineContext.Element`.
 *
 * 기존 [LeaderElectionInfo] 와 **별도 element** — binary-compat 보존 (Codex F5 / Type T8).
 * Aspect 가 `withContext(LeaderElectionInfo(...) + LockHandleElement(...))` 패턴으로 두 element 동시 push.
 *
 * ## 동작/계약
 * - `handle` property 는 `internal` — 외부 caller 는 [io.bluetape4k.leader.LockAssert] /
 *   [io.bluetape4k.leader.LockExtender] API 만 사용. handle metadata (token, slotId 등) 직접 접근 차단 (R3-F12).
 * - public companion `Key` 는 `currentCoroutineContext()[LockHandleElement]` 검사 가능 — element 존재 여부만 노출.
 *
 * ## Example
 * ```kotlin
 * // aspect 내부에서:
 * withContext(LeaderElectionInfo("job-A", true) + LockHandleElement(handle)) {
 *     userBody()  // 안에서 LockAssert.assertLockedSuspend() / LockExtender.extendActiveLockSuspend(d) 호출
 * }
 * ```
 */
@ConsistentCopyVisibility
data class LockHandleElement internal constructor(
    internal val handle: LeaderLockHandle,
) : CoroutineContext.Element {

    companion object Key : CoroutineContext.Key<LockHandleElement>

    override val key: CoroutineContext.Key<*> get() = Key
}
