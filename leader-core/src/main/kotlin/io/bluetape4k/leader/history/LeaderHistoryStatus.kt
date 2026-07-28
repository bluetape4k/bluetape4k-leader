package io.bluetape4k.leader.history

/**
 * `LeaderHistoryStatus`는 leader election의 현재 상태를 표현합니다.
 *
 * API 이름과 `lock`, `lease`, `leader`, `slot`, `audit` 용어는 코드 계약과 동일하게 유지합니다.
 */
enum class LeaderHistoryStatus {
    ACQUIRED,
    COMPLETED,
    FAILED,
    EXPIRED,
}
