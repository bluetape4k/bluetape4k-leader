package io.bluetape4k.leader.coroutines

import io.bluetape4k.leader.identity.LeaderIdSource
import io.bluetape4k.logging.KLogging
import kotlin.coroutines.CoroutineContext

/**
 * `LeaderElectionInfo` 선언은 leader election 계약에서 사용되는 data class입니다.
 *
 * API 이름과 `lock`, `lease`, `leader`, `slot`, `audit` 용어는 코드 계약과 동일하게 유지합니다.
 * @property lockName leader election에 사용할 lock 이름입니다. backend별 검증 규칙을 통과해야 하며 상태 조회와 audit의 기준 키가 됩니다.
 * @property wasElected `wasElected` 호출 또는 상태 계산에 필요한 값입니다.
 * @property leaderId audit에 기록할 leader identity입니다.
 * @property leaderIdSource `leaderIdSource` 호출 또는 상태 계산에 필요한 값입니다.
 */
data class LeaderElectionInfo(
    val lockName: String,
    val wasElected: Boolean,
    val leaderId: String? = null,
    val leaderIdSource: LeaderIdSource? = null,
) : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<LeaderElectionInfo>, KLogging()
    override val key: CoroutineContext.Key<*> get() = Key
}

/**
 * `LeaderElectionInfo` 호출은 leader election 계약의 일부 동작을 수행합니다.
 *
 * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
 */
fun LeaderElectionInfo.validate(): LeaderElectionInfo = apply {
    require((leaderId == null) == (leaderIdSource == null)) {
        "leaderId and leaderIdSource must both be null or both non-null: leaderId=$leaderId, leaderIdSource=$leaderIdSource"
    }
    require(!wasElected || leaderId != null) {
        "wasElected=true implies leaderId must be non-null: leaderId=$leaderId"
    }
}
