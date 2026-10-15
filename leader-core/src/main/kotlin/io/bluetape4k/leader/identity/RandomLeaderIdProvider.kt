package io.bluetape4k.leader.identity

import io.bluetape4k.codec.Base58
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireGt

/**
 * Base58 문자 집합에서 임의의 leader ID를 생성합니다.
 * @property length 생성할 ID 문자열의 문자 수. 0보다 커야 합니다.
 */
class RandomLeaderIdProvider(val length: Int = DefaultLength): LeaderIdProvider {

    init {
        length.requireGt(0, "length")
    }

    companion object: KLogging() {
        /**
         * 기본 leader ID 길이인 12자입니다.
         */
        const val DefaultLength: Int = 12

        /**
         * 기본 길이 12를 사용하는 재사용 가능한 provider입니다.
         */
        @JvmField
        val Default: LeaderIdProvider = RandomLeaderIdProvider()
    }

    override fun nextLeaderId(lockName: String): String = Base58.randomString(length)
}
