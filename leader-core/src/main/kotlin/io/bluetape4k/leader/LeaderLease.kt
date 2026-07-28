package io.bluetape4k.leader

import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireNotBlank
import java.io.Serializable
import java.time.Instant

/**
 * `LeaderLease`는 leader가 lock을 보유한 기간과 audit identity를 담는 lease snapshot입니다.
 *
 * API 이름과 `lock`, `lease`, `leader`, `slot`, `audit` 용어는 코드 계약과 동일하게 유지합니다.
 * @property auditLeaderId `auditLeaderId` 호출 또는 상태 계산에 필요한 값입니다.
 * @property electedAt `electedAt` 호출 또는 상태 계산에 필요한 값입니다.
 * @property leaseUntil `leaseUntil` 호출 또는 상태 계산에 필요한 값입니다.
 * @property slot group election slot과 audit leader id를 함께 전달하는 값입니다.
 * @property nodeId 상태 조회와 audit에 노출되는 노드 또는 인스턴스 식별자입니다.
 */
data class LeaderLease(
    val auditLeaderId: String,
    val electedAt: Instant? = null,
    val leaseUntil: Instant? = null,
    val slot: Int? = null,
    val nodeId: String? = null,
) : Serializable {

    companion object : KLogging() {
        private const val serialVersionUID = 2L
    }

    init {
        auditLeaderId.requireNotBlank("auditLeaderId")
        nodeId?.requireNotBlank("nodeId")
        require(slot == null || slot >= 0) { "slot must be null or non-negative: $slot" }
        if (electedAt != null && leaseUntil != null) {
            require(!leaseUntil.isBefore(electedAt)) {
                "leaseUntil must not be before electedAt: electedAt=$electedAt, leaseUntil=$leaseUntil"
            }
        }
    }

}
