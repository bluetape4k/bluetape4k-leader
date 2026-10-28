package io.bluetape4k.leader

import io.bluetape4k.leader.identity.LeaderIdProvider
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireNotBlank
import java.io.Serializable

/**
 * 그룹 선거에서 lock 이름과 이 요청을 대표하는 leader ID를 함께 전달합니다.
 *
 * 생성 시 두 값은 비어 있을 수 없고 ASCII 제어 문자를 포함할 수 없습니다. `lockName`에는 `{`와 `}`도 허용되지 않습니다.
 * @property lockName 그룹 선거와 lock 조회에 사용하는 이름입니다.
 * @property leaderId 선거·감사 기록에서 이 요청을 식별하는 leader ID입니다.
 */
data class LeaderSlot(
    val lockName: String,
    val leaderId: String,
): Serializable {

    init {
        lockName.validateLockName("lockName")
//        lockName.requireNotBlank("lockName")
//        require(lockName.none { it == '{' || it == '}' }) {
//            "lockName must not contain '{' or '}' (Redis Cluster hashtag injection risk): '$lockName'"
//        }
//        require(lockName.none { it.code < 0x20 || it.code == 0x7F }) {
//            "lockName must not contain control characters (log injection risk)"
//        }
        leaderId.requireNotBlank("leaderId")
        require(leaderId.none { it.code < 0x20 || it.code == 0x7F }) {
            "leaderId must not contain control characters (log injection risk)"
        }
    }

    companion object: KLogging() {
        private const val serialVersionUID = 1L

        /**
         * `lockName`에 대해 `provider`가 생성한 leader ID를 담은 `LeaderSlot`을 만듭니다. lock 획득은 시도하지 않습니다.
         * @param lockName leader election에 사용할 lock 이름입니다. backend별 검증 규칙을 통과해야 하며 상태 조회와 audit의 기준 키가 됩니다.
         * @param provider `lockName`에 대응하는 leader ID를 생성하는 공급자
         * @return 생성한 `LeaderSlot`
         */
        fun of(lockName: String, provider: LeaderIdProvider): LeaderSlot =
            LeaderSlot(lockName, provider.nextLeaderId(lockName))

        /**
         * 생성자 호출 문법으로 `lockName`과 그 이름에서 생성한 leader ID를 묶은 `LeaderSlot`을 만듭니다.
         * @param lockName leader election에 사용할 lock 이름입니다. backend별 검증 규칙을 통과해야 하며 상태 조회와 audit의 기준 키가 됩니다.
         * @param provider `lockName`에 대응하는 leader ID를 생성하는 공급자
         * @return 생성한 `LeaderSlot`
         */
        operator fun invoke(lockName: String, provider: LeaderIdProvider): LeaderSlot =
            LeaderSlot(lockName, provider.nextLeaderId(lockName))
    }
}
