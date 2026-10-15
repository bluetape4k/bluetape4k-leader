package io.bluetape4k.leader.internal

import io.bluetape4k.leader.LeaderLockHandle

/**
 * 현재 thread의 실제 lock handle을 일회성으로 꺼내는 내부 저장소입니다.
 */
internal object LeaderLockHandleCapture {
    // 가상 스레드 경로에 ScopedValue를 적용할지는 별도 설계 검토가 필요합니다.

    private val threadLocal: ThreadLocal<LeaderLockHandle.Real?> = ThreadLocal()

    fun set(handle: LeaderLockHandle.Real) {
        threadLocal.set(handle)
    }

    /**
     * 현재 thread에 저장된 handle을 반환한 뒤 저장소에서 제거합니다.
     * @return 저장된 handle이 없으면 `null`
     */
    fun poll(): LeaderLockHandle.Real? {
        val handle = threadLocal.get()
        threadLocal.remove()
        return handle
    }

    fun clear() {
        threadLocal.remove()
    }
}
