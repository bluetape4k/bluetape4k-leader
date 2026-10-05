package io.bluetape4k.leader.exposed.jdbc.lock

/** 해제 결과를 DB 반영 성공, 미소유, DB 오류로 구분합니다. */
internal enum class ExposedJdbcUnlockOutcome {
    RELEASED,
    NOT_HELD,
    FAILED,
}
