package io.bluetape4k.leader.annotation

/**
 * `@LeaderElection`과 `@LeaderGroupElection`에서 elector backend 오류를 처리하는 방식을 선택합니다.
 *
 * lock contention은 이 설정과 관계없이 skip으로 처리됩니다.
 */
enum class LeaderAspectFailureMode {
    /**
     * 공통 AOP 설정의 `failureMode` 값을 사용합니다.
     */
    INHERIT,

    /**
     * backend 오류를 예외로 전파합니다. 정상 contention은 skip입니다.
     */
    RETHROW,

    /**
     * backend 오류를 skip으로 처리하고 결과형에는 `Skipped`, 일반 반환형에는 기본값을 반환합니다.
     */
    SKIP,

    /**
     * backend 오류가 발생하면 lock 없이 대상 메서드를 실행합니다. 중복 실행 위험을 감수할 때만 사용합니다.
     */
    FAIL_OPEN_RUN,
}
