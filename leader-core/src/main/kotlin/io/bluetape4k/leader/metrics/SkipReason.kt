package io.bluetape4k.leader.metrics

/**
 * `LeaderAopMetricsRecorder.onLockNotAcquired` 호출 시 전달하는 미선출 사유.
 *
 * [#85] `LeaderRunResult` sealed SPI 도입으로 `CONTENTION` 은 이제 정확 — `runIfLeaderResult`
 * 내부 `elected` 플래그로 본문 `null` 반환과 미선출을 명확히 구분.
 *
 * `FAIL_OPEN_FORCED` 는 후속 [#81] 에서 `FAIL_OPEN_RUN` failureMode 도입 시 함께 추가.
 */
enum class SkipReason {
    /** waitTime 내 락 획득 실패. */
    CONTENTION,

    /** 백엔드 예외 발생 후 SKIP 모드로 흡수. */
    BACKEND_ERROR,
}
