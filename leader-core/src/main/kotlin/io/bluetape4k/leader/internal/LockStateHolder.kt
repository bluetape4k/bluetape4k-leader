package io.bluetape4k.leader.internal

import io.bluetape4k.leader.LeaderLockHandle

/**
 * `LockStateHolder`는 leader election의 현재 상태를 표현합니다.
 *
 * API 이름과 `lock`, `lease`, `leader`, `slot`, `audit` 용어는 코드 계약과 동일하게 유지합니다.
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

    /**
     * `cleanup` 호출은 leader election 계약의 일부 동작을 수행합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
     */
    fun cleanup() {
        if (tl.get().isEmpty()) {
            tl.remove()
        }
    }

    /**
     * `withPushed` 호출은 leader election 계약의 일부 동작을 수행합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @param handle `handle` 호출 또는 상태 계산에 필요한 값입니다.
     * @param block `block` 호출 또는 상태 계산에 필요한 값입니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
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
