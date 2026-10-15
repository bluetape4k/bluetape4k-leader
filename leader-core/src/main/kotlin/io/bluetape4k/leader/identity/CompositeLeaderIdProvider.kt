package io.bluetape4k.leader.identity

import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireNotBlank

/**
 * prefix와 separator 뒤에 delegate가 생성한 ID를 붙여 leader ID를 구성합니다.
 * @property prefix 결과 ID 앞에 붙일 비어 있지 않은 문자열
 * @property separator prefix와 delegate ID 사이의 문자열. 기본값은 `:`입니다.
 * @property delegate 뒤쪽 ID를 생성할 공급자. 기본값은 `RandomLeaderIdProvider.Default`입니다.
 */
class CompositeLeaderIdProvider(
    val prefix: String,
    val separator: String = ":",
    val delegate: LeaderIdProvider = RandomLeaderIdProvider.Default,
): LeaderIdProvider {

    companion object: KLogging()

    init {
        prefix.requireNotBlank("prefix")
    }

    override fun nextLeaderId(lockName: String): String =
        "$prefix$separator${delegate.nextLeaderId(lockName)}"
}
