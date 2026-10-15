package io.bluetape4k.leader.internal

import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.logging.KLogging

/**
 * `LockStateHolder`는 leader election의 현재 상태를 표현합니다.
 *
 * 현재 thread의 nested lock context를 LIFO stack으로 보관합니다. 다른 thread나 coroutine으로 자동 전파되지 않습니다.
 */
internal object LockStateHolder: KLogging() {

    private val tl: ThreadLocal<ArrayDeque<LeaderLockHandle>> =
        ThreadLocal.withInitial { ArrayDeque() }

    /** 현재 thread의 lock context 맨 앞에 handle을 추가합니다. */
    fun push(handle: LeaderLockHandle) {
        tl.get().addFirst(handle)
    }

    /** 현재 thread의 가장 최근 handle을 제거해 반환합니다. stack이 비어 있으면 `null`입니다. */
    fun pop(): LeaderLockHandle? = tl.get().removeFirstOrNull()

    /** 현재 thread에서 가장 최근에 push된 handle을 제거하지 않고 반환합니다. */
    fun peekSync(): LeaderLockHandle? = tl.get().firstOrNull()

    /** 현재 thread stack에서 이름이 [lockName]인 가장 최근 handle을 찾습니다. */
    fun peekSyncMatching(lockName: String): LeaderLockHandle? =
        tl.get().firstOrNull { it.lockName == lockName }

    /**
     * 현재 thread의 stack이 비어 있을 때 ThreadLocal 값을 제거해 thread 재사용 시 참조가 남지 않게 합니다.
     */
    fun cleanup() {
        if (tl.get().isEmpty()) {
            tl.remove()
        }
    }

    /**
     * block 실행 전 handle을 push하고 `finally`에서 pop/cleanup합니다.
     * @param handle block에서 현재 lock context로 조회할 handle
     * @param block lock context에서 실행할 작업
     * @return block의 반환값. 예외가 발생하면 그대로 전파됩니다.
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
