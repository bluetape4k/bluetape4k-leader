package io.bluetape4k.leader.exposed

/**
 * leader-exposed-core 모듈의 공유 상수.
 * 테이블 이름 및 컬럼 길이 제약을 중앙 관리.
 */
object ExposedLeaderConstants {

    /** 단일 리더 락 테이블 이름 */
    const val LOCK_TABLE_NAME = "bluetape4k_leader_locks"

    /** 그룹 리더 락 테이블 이름 (세마포어 기반 멀티 리더) */
    const val GROUP_LOCK_TABLE_NAME = "bluetape4k_leader_group_locks"

    /** 리더 선출 이력 테이블 이름 */
    const val LOCK_HISTORY_TABLE_NAME = "bluetape4k_leader_lock_history"

    /** lockName 컬럼 최대 길이 */
    const val LOCK_NAME_LENGTH = 255

    /** lockOwner 컬럼 최대 길이 */
    const val LOCK_OWNER_LENGTH = 255

    /** fencing token (UUID) 컬럼 길이 — UUID 표준 36자 */
    const val TOKEN_LENGTH = 36

    /** 이력 상태 컬럼 최대 길이 */
    const val STATUS_LENGTH = 20
}
