package io.bluetape4k.leader

/**
 * backend 또는 상태 snapshot이 보고하는 lock 점유 여부입니다.
 */
enum class LeaderStatus {
    /**
     * 현재 owner가 기록되지 않았습니다.
     */
    Empty,

    /**
     * 현재 owner가 기록되어 있습니다.
     */
    Occupied,
}
