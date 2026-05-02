package io.bluetape4k.leader.exposed.tables

/**
 * 리더 선출 이력 레코드의 상태.
 *
 * 라이프사이클: [ACQUIRED] → [COMPLETED] | [FAILED] | [EXPIRED]
 */
enum class HistoryStatus {

    /** 락 획득 성공 — 리더 action 실행 시작 */
    ACQUIRED,

    /** 리더 action 실행 완료 (정상 종료) */
    COMPLETED,

    /** 리더 action 실행 실패 (예외 발생) */
    FAILED,

    /** leaseTime 초과로 만료됨 — [LeaderLockHistoryTable.lockedUntil] 기준 */
    EXPIRED,
}
