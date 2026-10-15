package io.bluetape4k.leader

import io.bluetape4k.support.requireGe
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotBlank
import java.io.Serializable

/**
 * `LeaderGroupState`는 group leader lock의 현재 상태 snapshot입니다.
 *
 * @property lockName 상태를 조회한 group lock 이름입니다. 공백 문자열은 허용되지 않습니다.
 * @property maxLeaders 동시에 점유할 수 있는 slot 수입니다. `1` 이상이어야 합니다.
 * @property activeCount 현재 점유 중이라고 backend가 보고한 slot 수입니다. `0..maxLeaders` 범위여야 합니다.
 * @property leaders 조회에서 반환된 leader lease 목록입니다. 목록 크기는 `0..maxLeaders` 범위여야 합니다.
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
     * 현재 모든 slot이 점유되었는지 여부입니다. `activeCount == maxLeaders`일 때 `true`입니다.
     */
    val isFull: Boolean get() = activeCount >= maxLeaders

    /**
     * 현재 점유 중인 slot이 없는지 여부입니다. `activeCount == 0`일 때 `true`입니다.
     */
    val isEmpty: Boolean get() = activeCount == 0
}
