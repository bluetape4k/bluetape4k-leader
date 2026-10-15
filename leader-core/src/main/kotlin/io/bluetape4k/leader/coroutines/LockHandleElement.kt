package io.bluetape4k.leader.coroutines

import io.bluetape4k.leader.LeaderLockHandle
import kotlin.coroutines.CoroutineContext

/**
 * coroutine context에 현재 실행 중인 leader lock handle을 전달합니다.
 * @property handle 이 coroutine scope에서 검사하거나 조회할 lock handle
 */
data class LockHandleElement(
    internal val handle: LeaderLockHandle,
): CoroutineContext.Element {

    companion object Key: CoroutineContext.Key<LockHandleElement>

    override val key: CoroutineContext.Key<*> get() = Key
}
