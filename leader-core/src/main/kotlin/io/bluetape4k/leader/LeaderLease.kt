package io.bluetape4k.leader

import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.safeLet
import java.io.Serializable
import java.time.Instant

/**
 * `LeaderLease`는 leader가 lock을 보유한 기간과 audit identity를 담는 lease snapshot입니다.
 *
 * @property auditLeaderId lease 기록에 연결할 leader ID입니다. 공백 값은 허용되지 않습니다.
 * @property electedAt 이 lease를 획득한 절대 시각입니다. backend가 시각을 제공하지 않으면 `null`입니다.
 * @property leaseUntil backend가 보고한 lease 만료 절대 시각입니다. `electedAt`과 둘 다 있으면 이 값은 더 이르지 않아야 합니다.
 * @property slot group election에서 점유한 0부터 시작하는 slot 번호입니다. 단일 election 또는 알 수 없는 경우 `null`이며, 지정하면 음수일 수 없습니다.
 * @property nodeId lease를 획득한 노드 ID입니다. 알 수 없으면 `null`이고, 지정할 때 공백 값은 허용되지 않습니다.
 */
data class LeaderLease(
    val auditLeaderId: String,
    val electedAt: Instant? = null,
    val leaseUntil: Instant? = null,
    val slot: Int? = null,
    val nodeId: String? = null,
): Serializable {

    companion object: KLogging() {
        private const val serialVersionUID = 2L
    }

    init {
        auditLeaderId.requireNotBlank("auditLeaderId")
        nodeId?.requireNotBlank("nodeId")
        require(slot == null || slot >= 0) { "slot must be null or non-negative: $slot" }
        safeLet(electedAt, leaseUntil) { electedAt, leaseUntil ->
            require(!leaseUntil.isBefore(electedAt)) {
                "leaseUntil must not be before electedAt: electedAt=$electedAt, leaseUntil=$leaseUntil"
            }
        }
    }
}
