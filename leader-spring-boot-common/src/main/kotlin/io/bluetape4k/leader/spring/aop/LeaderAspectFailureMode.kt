package io.bluetape4k.leader.spring.aop

/**
 * 백엔드 예외 (Lettuce/Redisson/Mongo timeout 등) 발생 시 Aspect 의 처리 정책.
 *
 * ## 옵션
 * - [RETHROW] (default): 백엔드 예외를 [io.bluetape4k.leader.LeaderElectionException] / `LeaderGroupElectionException` 으로 wrapping 후 호출자에게 전파.
 *   message 일반화로 host/credentials 인프라 정보 누출 차단. cause 보존.
 * - [SKIP]: 백엔드 예외 흡수 후 `null` 반환 (ShedLock skip 동등).
 *
 * ## `FAIL_OPEN_RUN` 미지원
 * "락 실패 시에도 본문 실행" 옵션은 본 PR sync `T?` 시그니처로는 elected vs forced 구분 불가능.
 * sealed `LeaderResult<T> { Elected, Skipped, FailedOpenRun }` SPI 도입과 함께 후속 [#81].
 *
 * ## 본문 예외와 무관
 * 본 enum 은 backend 예외만 처리. 사용자 본문 (`pjp.proceed()`) throw 는 RETHROW 모드와 무관하게
 * 항상 그대로 전파된다 (wrapping 없음, [SKIP] 모드도 동일).
 */
enum class LeaderAspectFailureMode {
    /**
     * Sentinel — `LeaderAopProperties.failureMode` 전역 default 사용.
     * 어노테이션에서만 사용. `LeaderAopProperties.failureMode` default 는 [RETHROW].
     */
    INHERIT,

    /** 백엔드 예외를 wrapping 후 호출자에게 전파. */
    RETHROW,

    /** 백엔드 예외 흡수 후 `null` 반환 (ShedLock skip 동등). */
    SKIP,
}
