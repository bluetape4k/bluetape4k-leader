package io.bluetape4k.leader.coroutines

import io.bluetape4k.leader.identity.LeaderIdSource
import kotlin.coroutines.CoroutineContext

/**
 * 현재 coroutine context에 전파하는 lock 이름, 선출 여부 및 leader ID 출처입니다.
 *
 * @property lockName leader election에 사용할 lock 이름입니다. backend별 검증 규칙을 통과해야 하며 상태 조회와 audit의 기준 키가 됩니다.
 * @property wasElected 현재 coroutine 작업이 해당 lock의 leader로 선출됐는지 여부
 * @property leaderId audit에 기록할 leader identity입니다.
 * @property leaderIdSource `leaderId`를 생성한 공급자. ID가 없으면 이 값도 null이어야 합니다.
 */
data class LeaderElectionInfo(
    val lockName: String,
    val wasElected: Boolean,
    val leaderId: String? = null,
    val leaderIdSource: LeaderIdSource? = null,
): CoroutineContext.Element {
    companion object Key: CoroutineContext.Key<LeaderElectionInfo>

    override val key: CoroutineContext.Key<*> get() = Key
}

/**
 * leader ID와 공급자가 함께 설정됐는지, 선출된 상태에 leader ID가 있는지 검사합니다.
 * @return 모든 불변식을 만족하는 동일한 인스턴스
 * @throws IllegalArgumentException leader ID와 공급자 설정이 어긋나거나 선출됐는데 ID가 없으면
 */
fun LeaderElectionInfo.validate(): LeaderElectionInfo = apply {
    require((leaderId == null) == (leaderIdSource == null)) {
        "leaderId and leaderIdSource must both be null or both non-null: leaderId=$leaderId, leaderIdSource=$leaderIdSource"
    }
    require(!wasElected || leaderId != null) {
        "wasElected=true implies leaderId must be non-null: leaderId=$leaderId"
    }
}
