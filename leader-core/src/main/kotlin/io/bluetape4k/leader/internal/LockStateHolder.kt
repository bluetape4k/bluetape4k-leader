package io.bluetape4k.leader.internal

import io.bluetape4k.leader.LeaderLockHandle

/**
 * Sync ([io.bluetape4k.leader.LeaderElector] / [io.bluetape4k.leader.VirtualThreadLeaderElector]) 컨텍스트의
 * lock stack — ThreadLocal Deque.
 *
 * Suspend / Mono 컨텍스트는 `LockHandleElement` (CoroutineContext.Element) 사용 — 이 holder 미사용.
 *
 * ## 동작/계약
 * - per-thread `ArrayDeque<LeaderLockHandle>` 으로 nested 호출 추적
 * - Virtual thread 환경: ThreadLocal 은 carrier thread 가 아닌 virtual thread 에 바인딩 (Java 21 standard)
 *   → child 가 새 virtual thread 를 spawn 하면 inheritance 없음 (R3-F8 명시)
 * - aspect 가 try/finally 로 push/pop 보장 — [withPushed] inline helper 사용 권장
 */
internal object LockStateHolder {

    private val tl: ThreadLocal<ArrayDeque<LeaderLockHandle>> =
        ThreadLocal.withInitial { ArrayDeque() }

    fun push(handle: LeaderLockHandle) {
        tl.get().addFirst(handle)
    }

    fun pop(): LeaderLockHandle? = tl.get().removeFirstOrNull()

    fun peekSync(): LeaderLockHandle? = tl.get().firstOrNull()

    fun peekSyncMatching(lockName: String): LeaderLockHandle? =
        tl.get().firstOrNull { it.lockName == lockName }

    /** stack 이 비어있으면 ThreadLocal 제거 (pool thread 누수 방지). */
    fun cleanup() {
        if (tl.get().isEmpty()) {
            tl.remove()
        }
    }

    /**
     * push/pop 헬퍼 — 누수 차단.
     *
     * 사용 예:
     * ```kotlin
     * LockStateHolder.withPushed(handle) { pjp.proceed() }
     * ```
     */
    inline fun <R> withPushed(handle: LeaderLockHandle, block: () -> R): R {
        push(handle)
        try {
            return block()
        } finally {
            pop()
            cleanup()
        }
    }
}
