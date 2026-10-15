package io.bluetape4k.leader.strategy

import java.io.Serializable

/**
 * 전략 선출 과정에서 평가 또는 선택 대상에서 제외된 후보와 그 이유를 기록합니다.
 * @property candidate 전략 선출에서 평가 대상이 된 후보 노드 정보입니다.
 * @property reason 후보를 제외한 규칙이나 원인을 설명하는 문자열
 */
data class Elimination(
    val candidate: CandidateInfo,
    val reason: String,
): Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
