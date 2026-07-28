package io.bluetape4k.leader.history

import java.time.Instant

/**
 * `LeaderHistorySink`는 leader election audit/history 저장 계약을 표현합니다.
 *
 * API 이름과 `lock`, `lease`, `leader`, `slot`, `audit` 용어는 코드 계약과 동일하게 유지합니다.
 */
interface LeaderHistorySink {

    /**
     * `recordAcquired`는 leader election audit/history 저장 계약을 표현합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @param record `record` 호출 또는 상태 계산에 필요한 값입니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
     */
    fun recordAcquired(record: LeaderLockHistoryRecord): LeaderHistoryKey?

    /**
     * `recordCompleted`는 leader election audit/history 저장 계약을 표현합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @param key `key` 호출 또는 상태 계산에 필요한 값입니다.
     * @param finishedAt 사용자 작업이 종료된 wall-clock 시각입니다. 실행 중이면 null입니다.
     * @param durationMs 사용자 작업 실행 시간입니다. 실행 중이면 null입니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
     */
    fun recordCompleted(key: LeaderHistoryKey, finishedAt: Instant, durationMs: Long)

    /**
     * `recordFailed`는 leader election audit/history 저장 계약을 표현합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @param key `key` 호출 또는 상태 계산에 필요한 값입니다.
     * @param finishedAt 사용자 작업이 종료된 wall-clock 시각입니다. 실행 중이면 null입니다.
     * @param durationMs 사용자 작업 실행 시간입니다. 실행 중이면 null입니다.
     * @param errorType 작업 실패 시 예외의 fully-qualified class 이름입니다.
     * @param errorMessage 작업 실패 시 정제되고 길이가 제한된 예외 메시지입니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
     */
    fun recordFailed(
        key: LeaderHistoryKey,
        finishedAt: Instant,
        durationMs: Long,
        errorType: String?,
        errorMessage: String?,
    )

    /**
     * `deleteOlderThan` 호출은 leader election 계약의 일부 동작을 수행합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @param cutoff `cutoff` 호출 또는 상태 계산에 필요한 값입니다.
     * @param limit `limit` 호출 또는 상태 계산에 필요한 값입니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
     */
    fun deleteOlderThan(cutoff: Instant, limit: Int): Int = 0
}
