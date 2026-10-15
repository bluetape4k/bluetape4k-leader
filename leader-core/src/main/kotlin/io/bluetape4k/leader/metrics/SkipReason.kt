package io.bluetape4k.leader.metrics

/**
 * 선거 관측성 결과에서 작업을 실행하지 않았거나 lock 없이 실행한 이유를 구분합니다.
 */
enum class SkipReason {
    /**
     * lock 또는 group slot을 다른 owner가 점유해 작업을 건너뛰었습니다.
     */
    CONTENTION,

    /**
     * backend 오류로 작업을 안전하게 진행하지 않았습니다.
     */
    BACKEND_ERROR,

    /**
     * fail-open 정책에 따라 lock 없이 대상 작업을 실행했습니다.
     */
    FAIL_OPEN_FORCED,
}
