package io.bluetape4k.leader.history

import java.time.Instant

/**
 * lock 획득 기록을 suspend 방식으로 저장하고 작업 종료 시 해당 행을 갱신하는 history 저장 계약입니다.
 *
 */
interface SuspendLeaderHistorySink {
    /**
     * 획득 기록을 저장하고 이후 완료·실패 갱신에 쓸 key를 돌려줍니다.
     *
     * @param record lock 이름, 소유권 token, 획득 시각과 초기 상태를 담은 기록입니다.
     * @return 후속 갱신에 사용할 key입니다. history 저장을 지원하지 않는 sink는 `null`을 반환할 수 있습니다.
     */
    suspend fun recordAcquired(record: LeaderLockHistoryRecord): LeaderHistoryKey?

    /**
     * 저장된 획득 기록을 정상 완료 상태로 갱신합니다.
     *
     * @param key `recordAcquired`가 반환한 행 식별자와 token입니다.
     * @param finishedAt 사용자 작업 종료 절대 시각입니다.
     * @param durationMs 획득 시점부터 완료까지의 경과 시간(밀리초)입니다.
     */
    suspend fun recordCompleted(key: LeaderHistoryKey, finishedAt: Instant, durationMs: Long)

    /**
     * 저장된 획득 기록을 실패 상태로 갱신합니다.
     *
     * @param key `recordAcquired`가 반환한 행 식별자와 token입니다.
     * @param finishedAt 사용자 작업 종료 절대 시각입니다.
     * @param durationMs 획득 시점부터 실패까지의 경과 시간(밀리초)입니다.
     * @param errorType 실패 원인의 예외 클래스 이름입니다. 알 수 없으면 `null`입니다.
     * @param errorMessage 실패 원인의 로그 정제 메시지입니다. 값이 없으면 `null`입니다.
     */
    suspend fun recordFailed(
        key: LeaderHistoryKey,
        finishedAt: Instant,
        durationMs: Long,
        errorType: String?,
        errorMessage: String?,
    )

    /**
     * cutoff보다 먼저 시작한 기록을 정리합니다. 기본 구현은 저장소 미지원으로 간주해 삭제하지 않습니다.
     *
     * @param cutoff 이 시각보다 먼저 획득된 이력을 삭제 대상으로 삼습니다.
     * @param limit 한 번의 정리에서 요청하는 최대 삭제 행 수입니다. 제한 적용은 저장소 구현에 따르며 MongoDB sink는 이 값을 무시합니다.
     * @return 삭제한 이력 행 수입니다.
     */
    suspend fun deleteOlderThan(cutoff: Instant, limit: Int): Int = 0
}
