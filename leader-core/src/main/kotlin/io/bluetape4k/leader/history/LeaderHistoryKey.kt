package io.bluetape4k.leader.history

import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireNotBlank
import java.io.Serializable

/**
 * `LeaderHistoryKey`는 leader election audit/history 저장 계약을 표현합니다.
 *
 * API 이름과 `lock`, `lease`, `leader`, `slot`, `audit` 용어는 코드 계약과 동일하게 유지합니다.
 * @property id `id` 호출 또는 상태 계산에 필요한 값입니다.
 * @property historyId `historyId` 호출 또는 상태 계산에 필요한 값입니다.
 * @property lockName leader election에 사용할 lock 이름입니다. backend별 검증 규칙을 통과해야 하며 상태 조회와 audit의 기준 키가 됩니다.
 * @property token backend lock을 해제하거나 검증할 때 사용하는 소유권 token입니다.
 * @property slotId group election backend가 slot을 식별할 때 쓰는 값입니다.
 */
data class LeaderHistoryKey(
    /**
     * `id` 값은 leader election 계약에서 노출되는 상태 또는 설정 항목입니다.
     */
    val id: Long? = null,
    /**
     * `historyId`는 leader election audit/history 저장 계약을 표현합니다.
     */
    val historyId: String? = null,
    /**
     * `lockName` 값은 leader election 계약에서 노출되는 상태 또는 설정 항목입니다.
     */
    val lockName: String,
    /**
     * `token` 값은 leader election 계약에서 노출되는 상태 또는 설정 항목입니다.
     */
    val token: String,
    /**
     * `slotId` 값은 leader election 계약에서 노출되는 상태 또는 설정 항목입니다.
     */
    val slotId: String? = null,
) : Serializable {

    init {
        lockName.requireNotBlank("lockName")
        token.requireNotBlank("token")
    }

    // 이 key를 문자열 보간으로 로그에 남길 때 credential이 노출되지 않도록 token을 가립니다.
    override fun toString(): String =
        "LeaderHistoryKey(id=$id, historyId=$historyId, lockName=$lockName, token=***, slotId=$slotId)"

    companion object : KLogging() {
        private const val serialVersionUID = 1L
    }
}
