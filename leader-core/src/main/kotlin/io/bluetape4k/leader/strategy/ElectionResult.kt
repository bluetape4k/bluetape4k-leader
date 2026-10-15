package io.bluetape4k.leader.strategy

import java.io.Serializable

/**
 * 전략 선출의 winner와 제외 사유, 후보별 점수를 묶은 snapshot입니다.
 *
 * @property winner 전략 선출에서 최종 선택된 후보입니다. 후보가 없거나 선택할 수 없으면 null입니다.
 * @property eliminations 선출 과정에서 제외된 후보와 제외 사유 목록입니다.
 * @property scores 후보 node id별 계산 점수입니다.
 */
data class ElectionResult(
    val winner: CandidateInfo?,
    val eliminations: List<Elimination>,
    val scores: Map<String, Double> = emptyMap(),
): Serializable {
    companion object {
        private const val serialVersionUID = 1L

        /**
         * winner와 제외 사유가 없는 빈 결과입니다.
         */
        val EMPTY = ElectionResult(winner = null, eliminations = emptyList())
    }
}
