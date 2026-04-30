package io.bluetape4k.leader.strategy

/**
 * 선출에서 탈락한 후보와 그 사유를 담습니다.
 *
 * @property candidate 탈락 후보
 * @property reason 탈락 사유 (사람이 읽을 수 있는 설명)
 */
data class Elimination(
    val candidate: CandidateInfo,
    val reason: String,
)
