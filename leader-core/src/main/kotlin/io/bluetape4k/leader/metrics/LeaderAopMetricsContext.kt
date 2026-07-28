package io.bluetape4k.leader.metrics

import io.bluetape4k.leader.identity.LeaderIdSource
import io.bluetape4k.support.requireNotBlank

/**
 * `LeaderAopMetricsContext` 선언은 leader election 계약에서 사용되는 interface입니다.
 *
 * API 이름과 `lock`, `lease`, `leader`, `slot`, `audit` 용어는 코드 계약과 동일하게 유지합니다.
 */
sealed interface LeaderAopMetricsContext {

    /**
     * `Unknown` 선언은 leader election 계약에서 사용되는 object입니다.
     *
     * API 이름과 `lock`, `lease`, `leader`, `slot`, `audit` 용어는 코드 계약과 동일하게 유지합니다.
     */
    data object Unknown : LeaderAopMetricsContext

    /**
     * `Identified` 선언은 leader election 계약에서 사용되는 data class입니다.
     *
     * API 이름과 `lock`, `lease`, `leader`, `slot`, `audit` 용어는 코드 계약과 동일하게 유지합니다.
     * @property leaderId audit에 기록할 leader identity입니다.
     * @property leaderIdSource `leaderIdSource` 호출 또는 상태 계산에 필요한 값입니다.
     */
    data class Identified(
        val leaderId: String,
        val leaderIdSource: LeaderIdSource,
    ) : LeaderAopMetricsContext {
        init {
            leaderId.requireNotBlank("leaderId")
        }
    }

    companion object {
        /**
         * `Empty` 값은 leader election 계약에서 노출되는 상태 또는 설정 항목입니다.
         */
        @JvmField
        val Empty: LeaderAopMetricsContext = Unknown
    }
}
