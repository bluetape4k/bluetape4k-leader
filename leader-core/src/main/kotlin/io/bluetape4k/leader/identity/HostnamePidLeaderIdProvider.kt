package io.bluetape4k.leader.identity

import io.bluetape4k.codec.Base58
import io.bluetape4k.leader.LeaderNodeId
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireGt

/**
 * `HostnamePidLeaderIdProvider` 선언은 leader election 계약에서 사용되는 class입니다.
 *
 * API 이름과 `lock`, `lease`, `leader`, `slot`, `audit` 용어는 코드 계약과 동일하게 유지합니다.
 * @property suffixLength `suffixLength` 호출 또는 상태 계산에 필요한 값입니다.
 */
class HostnamePidLeaderIdProvider(val suffixLength: Int = 8) : LeaderIdProvider {

    init {
        suffixLength.requireGt(0, "suffixLength")
    }

    companion object : KLogging()

    override fun nextLeaderId(lockName: String): String =
        "${LeaderNodeId.Default}:${Base58.randomString(suffixLength)}"
}
