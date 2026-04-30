package io.bluetape4k.leader.strategy

/**
 * 선출 결과입니다.
 *
 * @property winner 선출된 리더. 후보가 없으면 `null`.
 * @property eliminations 탈락 후보 목록과 각 탈락 사유.
 * @property scores 후보별 점수 (nodeId → score). 점수 기반 전략에서만 채워집니다.
 */
data class ElectionResult(
    val winner: CandidateInfo?,
    val eliminations: List<Elimination>,
    val scores: Map<String, Double> = emptyMap(),
) {
    companion object {
        /** 후보 없음 — winner도 탈락자도 없는 빈 결과. */
        val EMPTY = ElectionResult(winner = null, eliminations = emptyList())
    }
}
