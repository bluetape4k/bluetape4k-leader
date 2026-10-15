package io.bluetape4k.leader.exposed

/**
 * `ExposedLeaderConstants`는 Exposed leader용 테이블 이름과 lock/history 열 길이 상수를 제공합니다.
 *
 */
object ExposedLeaderConstants {

    /** 단일 leader lock의 현재 소유권과 lease 시각을 저장하는 테이블 이름입니다. */
    const val LOCK_TABLE_NAME = "bluetape4k_leader_locks"

    /** group leader election의 `(lockName, slot)`별 소유권과 lease 시각을 저장하는 테이블 이름입니다. */
    const val GROUP_LOCK_TABLE_NAME = "bluetape4k_leader_group_locks"

    /** lock 획득·완료·실패 이력을 저장하는 테이블 이름입니다. */
    const val LOCK_HISTORY_TABLE_NAME = "bluetape4k_leader_lock_history"

    /** lock 이름을 저장하는 VARCHAR 열의 최대 길이입니다. */
    const val LOCK_NAME_LENGTH = 255

    /** 선택적 `lockOwner` 식별자를 저장하는 VARCHAR 열의 최대 길이입니다. */
    const val LOCK_OWNER_LENGTH = 255

    /** lock 소유권 검증 token을 저장하는 VARCHAR 열의 최대 길이입니다. token 값 자체는 로그에 남기지 마세요. */
    const val TOKEN_LENGTH = 36

    /** `LeaderHistoryStatus` enum 이름을 저장하는 열의 최대 길이입니다. */
    const val STATUS_LENGTH = 20
}
