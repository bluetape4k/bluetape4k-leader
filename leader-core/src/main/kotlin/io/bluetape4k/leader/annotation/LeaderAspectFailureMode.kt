package io.bluetape4k.leader.annotation

/**
 * 리더 락 획득 실패 (백엔드 예외 또는 경쟁) 시 Aspect 의 처리 정책.
 *
 * ## 옵션
 * - [RETHROW] (default): 백엔드 예외를 [io.bluetape4k.leader.LeaderElectionException] / `LeaderGroupElectionException` 으로 wrapping 후 호출자에게 전파.
 *   message 일반화로 host/credentials 인프라 정보 누출 차단. cause 보존.
 * - [SKIP]: 락 획득 실패 또는 백엔드 예외 시 흡수 후 `null` 반환 (ShedLock skip 동등).
 * - [FAIL_OPEN_RUN]: 락 획득 실패 또는 백엔드 예외 시 락 없이 본문을 그대로 실행.
 *   락 시스템 장애 상황에서 중단보다 계속 실행이 나은 circuit-breaker 시나리오용.
 *   **주의**: 복수 인스턴스가 동시에 본문을 실행할 수 있으므로 idempotent 작업에만 사용.
 *
 * ## 본문 예외와 무관
 * 본 enum 은 락 획득 실패 처리만 담당. 사용자 본문 (`pjp.proceed()`) throw 는 failureMode 와 무관하게
 * 항상 그대로 전파된다 (wrapping 없음).
 */
enum class LeaderAspectFailureMode {
    /**
     * Sentinel — `LeaderAopProperties.failureMode` 전역 default 사용.
     * 어노테이션에서만 사용. `LeaderAopProperties.failureMode` default 는 [RETHROW].
     */
    INHERIT,

    /** 백엔드 예외를 wrapping 후 호출자에게 전파. */
    RETHROW,

    /** 락 획득 실패 또는 백엔드 예외 흡수 후 `null` 반환 (ShedLock skip 동등). */
    SKIP,

    /**
     * 락 획득 실패 또는 백엔드 예외 시 락 없이 본문을 실행 (fail-open).
     *
     * - 경쟁으로 Skipped → `onLockNotAcquired(FAIL_OPEN_FORCED)` emit 후 본문 실행
     * - 백엔드 예외 → 경고 로그 후 본문 실행
     *
     * **주의**: 락이 없으므로 복수 인스턴스 동시 실행 가능. idempotent 작업에만 사용.
     */
    FAIL_OPEN_RUN,
}
