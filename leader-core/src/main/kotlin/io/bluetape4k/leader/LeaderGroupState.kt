package io.bluetape4k.leader

import io.bluetape4k.support.requireGe
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotBlank
import java.io.Serializable

/**
 * `LeaderGroupState`는 group leader lock의 현재 상태 snapshot입니다.
 *
 * API 이름과 `lock`, `lease`, `leader`, `slot`, `audit` 용어는 코드 계약과 동일하게 유지합니다.
 * @property lockName leader election에 사용할 lock 이름입니다. backend별 검증 규칙을 통과해야 하며 상태 조회와 audit의 기준 키가 됩니다.
 * @property maxLeaders 동시에 leadership을 획득할 수 있는 최대 슬롯 수입니다.
 * @property activeCount 현재 점유 중인 leader slot 수입니다.
 * @property leaders 현재 group lock을 점유한 leader lease 목록입니다.
 */
data class LeaderGroupState(
    val lockName: String,
    val maxLeaders: Int,
    val activeCount: Int,
    val leaders: List<LeaderLease> = emptyList(),
): Serializable {

    companion object {
        private const val serialVersionUID = 1L
    }

    init {
        lockName.requireNotBlank("lockName")
        maxLeaders.requireGe(1, "maxLeaders")
        activeCount.requireInRange(0, maxLeaders, "activeCount")
        leaders.size.requireInRange(0, maxLeaders, "leaders.size")
    }

    /**
     * `availableSlots`는 아직 획득 가능한 group leader slot 수를 조회합니다.
     */
    val availableSlots: Int get() = maxLeaders - activeCount

    /**
     * `isFull` 값은 leader election 계약에서 노출되는 상태 또는 설정 항목입니다.
     */
    val isFull: Boolean get() = activeCount >= maxLeaders

    /**
     * `isEmpty` 값은 leader election 계약에서 노출되는 상태 또는 설정 항목입니다.
     */
    val isEmpty: Boolean get() = activeCount == 0
}
