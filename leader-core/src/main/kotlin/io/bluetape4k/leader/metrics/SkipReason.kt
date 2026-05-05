package io.bluetape4k.leader.metrics

/**
 * `LeaderAopMetricsRecorder.onLockNotAcquired` 호출 시 전달하는 미선출 사유.
 *
 * [#85] `LeaderRunResult` sealed SPI 도입으로 `CONTENTION` 은 이제 정확 — `runIfLeaderResult`
 * 내부 `elected` 플래그로 본문 `null` 반환과 미선출을 명확히 구분.
 */
enum class SkipReason {
    /** waitTime 내 락 획득 실패. */
    CONTENTION,

    /** 백엔드 예외 발생 후 SKIP 모드로 흡수. */
    BACKEND_ERROR,

    /**
     * 락 미획득(경쟁) 또는 백엔드 예외 발생 후 `FAIL_OPEN_RUN` 모드로 락 없이 본문 실행.
     * `onLockNotAcquired` 이벤트 발행 후 본문이 정상 실행되면 `onTaskFinished` 가 이어서 발행된다.
     */
    FAIL_OPEN_FORCED,
}
