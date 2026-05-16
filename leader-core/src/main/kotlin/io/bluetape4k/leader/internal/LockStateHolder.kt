package io.bluetape4k.leader.internal

import io.bluetape4k.leader.LeaderLockHandle

/**
 * Lock stack for the sync ([io.bluetape4k.leader.LeaderElector] / [io.bluetape4k.leader.VirtualThreadLeaderElector])
 * context — a ThreadLocal Deque.
 *
 * Suspend / Mono contexts use `LockHandleElement` (CoroutineContext.Element) and do not use this holder.
 *
 * ## Behavior / Contract
 * - Tracks nested calls via a per-thread `ArrayDeque<LeaderLockHandle>`
 * - In virtual thread environments: ThreadLocal binds to the virtual thread, not the carrier thread (Java 21 standard)
 *   → no inheritance when a child spawns a new virtual thread (R3-F8 explicit)
 * - The aspect guarantees push/pop via try/finally — use the [withPushed] inline helper
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

    /** Removes the ThreadLocal when the stack is empty (prevents pool thread leaks). */
    fun cleanup() {
        if (tl.get().isEmpty()) {
            tl.remove()
        }
    }

    /**
     * push/pop helper — prevents leaks.
     *
     * ## Usage
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
