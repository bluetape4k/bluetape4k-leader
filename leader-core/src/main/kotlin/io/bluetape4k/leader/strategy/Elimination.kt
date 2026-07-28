package io.bluetape4k.leader.strategy

import java.io.Serializable

/**
 * `Elimination`는 전략 선출에서 제외된 후보와 사유입니다.
 *
 * API 이름과 `lock`, `lease`, `leader`, `slot`, `audit` 용어는 코드 계약과 동일하게 유지합니다.
 * @property candidate 전략 선출에서 평가 대상이 된 후보 노드 정보입니다.
 * @property reason `reason` 호출 또는 상태 계산에 필요한 값입니다.
 */
data class Elimination(
    val candidate: CandidateInfo,
    val reason: String,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
