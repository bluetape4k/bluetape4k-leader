package io.bluetape4k.leader.history

/**
 * 저장된 lock history record의 lifecycle 단계를 나타냅니다.
 */
enum class LeaderHistoryStatus {
    ACQUIRED,
    COMPLETED,
    FAILED,
    EXPIRED,
}
