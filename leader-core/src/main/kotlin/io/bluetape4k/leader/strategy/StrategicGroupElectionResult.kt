package io.bluetape4k.leader.strategy

import java.io.Serializable

/**
 * `StrategicGroupElectionResult`는 strategic group 선출 결과입니다.
 *
 * `winners`는 선출 우선순위가 높은 순서로 정렬되며, `eliminations`는 선택되지
 * 않은 후보를 담습니다. `scores`는 점수 전략이 계산한 후보별 점수입니다.
 */
data class StrategicGroupElectionResult(
    val winners: List<CandidateInfo>,
    val eliminations: List<Elimination>,
    val scores: Map<String, Double> = emptyMap(),
) : Serializable {

    companion object {
        private const val serialVersionUID = 1L

        /** 후보가 없어 아무도 선택되지 않은 결과입니다. */
        @JvmField
        val EMPTY = StrategicGroupElectionResult(
            winners = emptyList(),
            eliminations = emptyList(),
        )
    }
}
