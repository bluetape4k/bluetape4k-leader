package io.bluetape4k.leader

import io.bluetape4k.leader.identity.LeaderIdProvider
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireNotBlank
import java.io.Serializable

/**
 * `LeaderSlot`는 lock 이름과 audit leader id를 함께 전달하는 slot 요청입니다.
 *
 * API 이름과 `lock`, `lease`, `leader`, `slot`, `audit` 용어는 코드 계약과 동일하게 유지합니다.
 * @property lockName leader election에 사용할 lock 이름입니다. backend별 검증 규칙을 통과해야 하며 상태 조회와 audit의 기준 키가 됩니다.
 * @property leaderId audit에 기록할 leader identity입니다.
 */
data class LeaderSlot(
    val lockName: String,
    val leaderId: String,
) : Serializable {

    init {
        lockName.requireNotBlank("lockName")
        require(lockName.none { it == '{' || it == '}' }) {
            "lockName must not contain '{' or '}' (Redis Cluster hashtag injection risk): '$lockName'"
        }
        require(lockName.none { it.code < 0x20 || it.code == 0x7F }) {
            "lockName must not contain control characters (log injection risk)"
        }
        leaderId.requireNotBlank("leaderId")
        require(leaderId.none { it.code < 0x20 || it.code == 0x7F }) {
            "leaderId must not contain control characters (log injection risk)"
        }
    }

    companion object : KLogging() {
        private const val serialVersionUID = 1L

        /**
         * `of` 호출은 leader election 계약의 일부 동작을 수행합니다.
         *
         * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
         * @param lockName leader election에 사용할 lock 이름입니다. backend별 검증 규칙을 통과해야 하며 상태 조회와 audit의 기준 키가 됩니다.
         * @param provider `provider` 호출 또는 상태 계산에 필요한 값입니다.
         * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
         */
        fun of(lockName: String, provider: LeaderIdProvider): LeaderSlot =
            LeaderSlot(lockName, provider.nextLeaderId(lockName))
    }
}
