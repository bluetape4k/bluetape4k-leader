package io.bluetape4k.leader.metrics

/**
 * `LeaderAopMetricsRecorder.onLockNotAcquired` 호출 시 전달하는 미선출 사유.
 *
 * ## best-effort 한계
 * 코어 SPI `runIfLeader(name, action): T?` 는 본문 `null` 반환과 미선출 구분 불가.
 * 본 PR `CONTENTION` 은 best-effort — 본문이 정상 `null` 반환한 경우도 CONTENTION 으로 기록될 수 있음.
 * 정확한 elected vs skipped 분리는 후속 [#85] sealed `LeaderRunResult` SPI 도입 시 가능.
 *
 * `FAIL_OPEN_FORCED` 는 후속 [#81] 에서 `FAIL_OPEN_RUN` failureMode 도입 시 함께 추가.
 */
enum class SkipReason {
    /** waitTime 내 락 획득 실패 (또는 본문 null 반환 — best-effort). */
    CONTENTION,

    /** 백엔드 예외 발생 후 SKIP 모드로 흡수. */
    BACKEND_ERROR,
}
