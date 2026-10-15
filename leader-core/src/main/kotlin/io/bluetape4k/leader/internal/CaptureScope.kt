package io.bluetape4k.leader.internal

import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug

/**
 * 현재 thread에서 조회할 수 있도록 실제 lock handle을 사용자 action 실행 구간에 임시 저장합니다.
 */
internal object CaptureScope: KLogging() {

    /**
     * `action`의 반환값을 그대로 반환하며, action이 예외를 던져도 저장한 handle을 제거합니다.
     * @param handle action 안에서 조회하게 할 실제 lock handle
     * @param action handle 캡처 범위에서 실행할 작업
     * @return action이 반환한 값
     */
    inline fun <T> runWithCapture(handle: LeaderLockHandle.Real, action: () -> T): T {
        log.debug { "runWithCapture. handle=$handle" }

        LeaderLockHandleCapture.set(handle)
        try {
            return action()
        } finally {
            LeaderLockHandleCapture.clear()
        }
    }
}
