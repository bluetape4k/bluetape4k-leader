package io.bluetape4k.leader.coroutines

import io.bluetape4k.leader.LeaderLockHandle
import kotlin.coroutines.CoroutineContext

/**
 * `LockHandleElement` 선언은 leader election 계약에서 사용되는 data class입니다.
 *
 * API 이름과 `lock`, `lease`, `leader`, `slot`, `audit` 용어는 코드 계약과 동일하게 유지합니다.
 * @property handle `handle` 호출 또는 상태 계산에 필요한 값입니다.
 */
data class LockHandleElement(
    internal val handle: LeaderLockHandle,
): CoroutineContext.Element {

    companion object Key: CoroutineContext.Key<LockHandleElement>

    override val key: CoroutineContext.Key<*> get() = Key
}
