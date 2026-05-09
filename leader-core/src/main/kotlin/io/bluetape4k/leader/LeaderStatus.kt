package io.bluetape4k.leader

/**
 * 단일 리더 선출의 현재 점유 상태를 표현합니다.
 *
 * ## 계약
 * - [Empty]는 현재 리더가 없음을 의미합니다.
 * - [Occupied]는 현재 리더가 선출되어 lease를 보유 중임을 의미합니다.
 *
 * ```kotlin
 * val state = election.state("daily-job")
 * if (state.status == LeaderStatus.Occupied) {
 *     println(state.leader?.leaderId)
 * }
 * ```
 */
enum class LeaderStatus {
    /** 현재 리더가 없습니다. */
    Empty,

    /** 현재 리더가 선출되어 있습니다. */
    Occupied,
}
