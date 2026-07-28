package io.bluetape4k.leader.exposed

/**
 * `ExposedLeaderConstants`는 Exposed database backend의 leader election, lock lease, ownership 확인을 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 */
object ExposedLeaderConstants {

    /**
     * `LOCK_TABLE_NAME` 값은 Exposed database backend leader election 계약에서 사용하는 설정 또는 상태 항목입니다.
     */
    const val LOCK_TABLE_NAME = "bluetape4k_leader_locks"

    /**
     * `GROUP_LOCK_TABLE_NAME` 값은 Exposed database backend leader election 계약에서 사용하는 설정 또는 상태 항목입니다.
     */
    const val GROUP_LOCK_TABLE_NAME = "bluetape4k_leader_group_locks"

    /**
     * `LOCK_HISTORY_TABLE_NAME` 값은 Exposed database backend leader election 계약에서 사용하는 설정 또는 상태 항목입니다.
     */
    const val LOCK_HISTORY_TABLE_NAME = "bluetape4k_leader_lock_history"

    /**
     * `LOCK_NAME_LENGTH` 값은 Exposed database backend leader election 계약에서 사용하는 설정 또는 상태 항목입니다.
     */
    const val LOCK_NAME_LENGTH = 255

    /**
     * `LOCK_OWNER_LENGTH` 값은 Exposed database backend leader election 계약에서 사용하는 설정 또는 상태 항목입니다.
     */
    const val LOCK_OWNER_LENGTH = 255

    /**
     * `TOKEN_LENGTH` 값은 Exposed database backend leader election 계약에서 사용하는 설정 또는 상태 항목입니다.
     */
    const val TOKEN_LENGTH = 36

    /**
     * `STATUS_LENGTH` 값은 Exposed database backend leader election 계약에서 사용하는 설정 또는 상태 항목입니다.
     */
    const val STATUS_LENGTH = 20
}
