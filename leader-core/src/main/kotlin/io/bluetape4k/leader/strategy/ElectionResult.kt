package io.bluetape4k.leader.strategy

import java.io.Serializable

/**
 * `ElectionResult`는 전략 선출 결과입니다.
 *
 * API 이름과 `lock`, `lease`, `leader`, `slot`, `audit` 용어는 코드 계약과 동일하게 유지합니다.
 * @property winner 전략 선출에서 최종 선택된 후보입니다. 후보가 없거나 선택할 수 없으면 null입니다.
 * @property eliminations 선출 과정에서 제외된 후보와 제외 사유 목록입니다.
 * @property scores 후보 node id별 계산 점수입니다.
 */
data class ElectionResult(
    val winner: CandidateInfo?,
    val eliminations: List<Elimination>,
    val scores: Map<String, Double> = emptyMap(),
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L

        /**
         * `EMPTY` 값은 leader election 계약에서 노출되는 상태 또는 설정 항목입니다.
         */
        val EMPTY = ElectionResult(winner = null, eliminations = emptyList())
    }
}
