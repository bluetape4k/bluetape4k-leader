package io.bluetape4k.leader.history

import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireNotBlank
import java.io.Serializable

/**
 * `LeaderHistoryKey`는 잠금 이력의 후속 갱신에 필요한 저장 식별자와 소유권 정보를 담습니다.
 *
 * @property id Exposed history table에서 생성한 행 ID입니다. 다른 저장소가 별도 ID를 사용하면 `null`입니다.
 * @property historyId 저장소가 발급한 이력 식별자입니다. MongoDB는 UUID v7 문자열을 사용하며, 별도 식별자가 없으면 `null`입니다.
 * @property lockName 이 이력이 가리키는 lock 이름입니다. 공백 문자열은 허용되지 않습니다.
 * @property token backend가 해당 lock의 소유권을 확인하고 이력을 연결하는 token입니다. 공백 문자열은 허용되지 않으며 민감정보이므로 로그에 직접 남기지 마세요.
 * `toString()`은 값을 가립니다.
 * @property slotId group election에서 획득한 슬롯 식별자입니다. 단일 leader 이력처럼 슬롯이 없으면 `null`입니다.
 */
data class LeaderHistoryKey(
    val id: Long? = null,
    val historyId: String? = null,
    val lockName: String,
    val token: String,
    val slotId: String? = null,
): Serializable {

    init {
        lockName.requireNotBlank("lockName")
        token.requireNotBlank("token")
    }

    // 이 key를 문자열 보간으로 로그에 남길 때 credential이 노출되지 않도록 token을 가립니다.
    override fun toString(): String =
        "LeaderHistoryKey(id=$id, historyId=$historyId, lockName=$lockName, token=***, slotId=$slotId)"

    companion object: KLogging() {
        private const val serialVersionUID = 1L
    }
}
