package io.bluetape4k.leader.strategy

/**
 * 리더로 선출된 노드의 작업 실행 결과를 나타냅니다.
 */
enum class CandidateResult {
    /** 작업 성공 */
    SUCCESS,

    /** 작업 실패 (예외 발생 포함) */
    FAILURE,
}
