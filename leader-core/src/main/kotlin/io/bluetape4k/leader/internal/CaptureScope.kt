package io.bluetape4k.leader.internal

import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.leader.coroutines.LockHandleElement

/**
 * `CaptureScope` 선언은 leader election 계약에서 사용되는 object입니다.
 *
 * API 이름과 `lock`, `lease`, `leader`, `slot`, `audit` 용어는 코드 계약과 동일하게 유지합니다.
 */
internal object CaptureScope {

    /**
     * `runWithCapture` 호출은 leader election 계약의 일부 동작을 수행합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @param handle `handle` 호출 또는 상태 계산에 필요한 값입니다.
     * @param action leadership을 획득한 경우에만 실행되는 사용자 작업입니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
     */
    inline fun <T> runWithCapture(handle: LeaderLockHandle.Real, action: () -> T): T {
        LeaderLockHandleCapture.set(handle)
        try {
            return action()
        } finally {
            LeaderLockHandleCapture.clear()
        }
    }
}
