package io.bluetape4k.leader.spring.aop.internal

/**
 * `@LeaderElection` / `@LeaderGroupElection` aspect 의 실행 분기.
 *
 * `resolveMetadata` 에서 method signature 를 기반으로 결정되며 `AdviceMetadata` 에 저장됩니다.
 *
 * ## 명명 — 카테고리 기반 (개별 detection marker 와 구분)
 * - [SYNC]: 일반 blocking 동기 메서드
 * - [COROUTINES]: Kotlin coroutine 계열 (현재 suspend method, 향후 Flow 등 확장 여지)
 * - [REACTIVE]: Reactor / Project Reactor 계열 (현재 `Mono`, 향후 `Flux` 등 확장 여지)
 *
 * 개별 method 의 정확한 detection (`Continuation` 파라미터 / `Mono` 반환) 은
 * `AdviceMetadata.isSuspend` / `AdviceMetadata.isMono` 속성으로 표현됩니다.
 */
internal enum class AdviceBranch {
    /** 일반 blocking 동기 메서드 */
    SYNC,

    /** Kotlin coroutine 기반 (현재 suspend method — 마지막 파라미터 `kotlin.coroutines.Continuation`) */
    COROUTINES,

    /** Reactor 기반 (현재 `reactor.core.publisher.Mono` 반환 non-suspend 메서드) */
    REACTIVE,
}
