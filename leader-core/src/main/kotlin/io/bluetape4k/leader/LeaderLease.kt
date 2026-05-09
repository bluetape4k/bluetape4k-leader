package io.bluetape4k.leader

import io.bluetape4k.support.requireNotBlank
import java.io.Serializable
import java.time.Instant

/**
 * 리더로 선출된 노드 또는 슬롯 점유자의 lease 스냅샷입니다.
 *
 * ## 계약
 * - [leaderId]는 가능한 경우 노드 식별자이며, backend가 노드 식별자를 별도로 저장하지 않으면
 *   fencing token 또는 backend holder id가 들어갈 수 있습니다.
 * - [electedAt]과 [leaseUntil]은 backend가 제공하는 범위에서 채워지는 best-effort 값입니다.
 * - [slot]은 그룹 리더 선출에서만 사용하며, 단일 리더 선출에서는 `null`입니다.
 *
 * ```kotlin
 * val lease = LeaderLease(
 *     leaderId = "node-a",
 *     electedAt = Instant.now(),
 * )
 * ```
 */
data class LeaderLease(
    val leaderId: String,
    val electedAt: Instant? = null,
    val leaseUntil: Instant? = null,
    val slot: Int? = null,
) : Serializable {

    companion object {
        private const val serialVersionUID = 1L
    }

    init {
        leaderId.requireNotBlank("leaderId")
        require(slot == null || slot >= 0) { "slot must be null or non-negative: $slot" }
        if (electedAt != null && leaseUntil != null) {
            require(!leaseUntil.isBefore(electedAt)) {
                "leaseUntil must not be before electedAt: electedAt=$electedAt, leaseUntil=$leaseUntil"
            }
        }
    }
}
