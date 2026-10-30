package io.bluetape4k.leader.identity

import io.bluetape4k.codec.Base58
import io.bluetape4k.leader.LeaderNodeId
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requirePositiveNumber

/**
 * 기본 node ID와 임의 Base58 suffix를 결합해 실행마다 다른 leader ID를 생성합니다.
 * @property suffixLength 임의 suffix의 문자 수. 0보다 커야 합니다.
 */
class HostnamePidLeaderIdProvider(val suffixLength: Int = 8): LeaderIdProvider {

    companion object: KLogging()

    init {
        suffixLength.requirePositiveNumber("suffixLength")
    }

    override fun nextLeaderId(lockName: String): String =
        "${LeaderNodeId.Default}:${Base58.randomString(suffixLength)}"
}
