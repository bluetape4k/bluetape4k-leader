package io.bluetape4k.leader.identity

import io.bluetape4k.codec.Base58
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireGt

/**
 * `RandomLeaderIdProvider` 선언은 leader election 계약에서 사용되는 class입니다.
 *
 * API 이름과 `lock`, `lease`, `leader`, `slot`, `audit` 용어는 코드 계약과 동일하게 유지합니다.
 * @property length `length` 호출 또는 상태 계산에 필요한 값입니다.
 */
class RandomLeaderIdProvider(val length: Int = DefaultLength) : LeaderIdProvider {

    init {
        length.requireGt(0, "length")
    }

    companion object : KLogging() {
        /**
         * `DefaultLength` 값은 leader election 계약에서 노출되는 상태 또는 설정 항목입니다.
         */
        const val DefaultLength: Int = 12

        /**
         * `Default` 값은 leader election 계약에서 노출되는 상태 또는 설정 항목입니다.
         */
        @JvmField
        val Default: LeaderIdProvider = RandomLeaderIdProvider()
    }

    override fun nextLeaderId(lockName: String): String = Base58.randomString(length)
}
