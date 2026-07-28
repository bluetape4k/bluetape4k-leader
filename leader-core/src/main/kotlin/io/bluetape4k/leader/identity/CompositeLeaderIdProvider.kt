package io.bluetape4k.leader.identity

import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireNotBlank

/**
 * `CompositeLeaderIdProvider` 선언은 leader election 계약에서 사용되는 class입니다.
 *
 * API 이름과 `lock`, `lease`, `leader`, `slot`, `audit` 용어는 코드 계약과 동일하게 유지합니다.
 * @property prefix `prefix` 호출 또는 상태 계산에 필요한 값입니다.
 * @property separator `separator` 호출 또는 상태 계산에 필요한 값입니다.
 * @property delegate 실제 leader election 동작을 수행하는 위임 객체입니다.
 */
class CompositeLeaderIdProvider(
    val prefix: String,
    val separator: String = ":",
    val delegate: LeaderIdProvider = RandomLeaderIdProvider.Default,
) : LeaderIdProvider {

    init {
        prefix.requireNotBlank("prefix")
    }

    companion object : KLogging()

    override fun nextLeaderId(lockName: String): String =
        "$prefix$separator${delegate.nextLeaderId(lockName)}"
}
