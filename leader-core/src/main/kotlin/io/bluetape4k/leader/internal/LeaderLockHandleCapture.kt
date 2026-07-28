package io.bluetape4k.leader.internal

import io.bluetape4k.leader.LeaderLockHandle

/**
 * `LeaderLockHandleCapture` 선언은 leader election 계약에서 사용되는 object입니다.
 *
 * API 이름과 `lock`, `lease`, `leader`, `slot`, `audit` 용어는 코드 계약과 동일하게 유지합니다.
 */
internal object LeaderLockHandleCapture {

    private val tl: ThreadLocal<LeaderLockHandle.Real?> = ThreadLocal()

    fun set(handle: LeaderLockHandle.Real) {
        tl.set(handle)
    }

    /**
     * `poll` 호출은 leader election 계약의 일부 동작을 수행합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
     */
    fun poll(): LeaderLockHandle.Real? {
        val handle = tl.get()
        tl.remove()
        return handle
    }

    fun clear() {
        tl.remove()
    }
}
