package io.bluetape4k.leader.coroutines

import kotlin.coroutines.CoroutineContext

/**
 * 리더 선출 결과를 CoroutineContext 로 전파하는 element.
 *
 * `@LeaderElection` / `@LeaderGroupElection` AOP 본문 실행 시 aspect 가 주입.
 * suspend / `Mono` 반환 메서드에서 `coroutineContext[LeaderElectionInfo]` 로 접근 가능.
 *
 * @property lockName 선출에 사용된 락 이름
 * @property wasElected 리더로 선출되어 본문이 실행 중인지 여부
 */
data class LeaderElectionInfo(
    val lockName: String,
    val wasElected: Boolean,
) : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<LeaderElectionInfo>
    override val key: CoroutineContext.Key<*> get() = Key
}
