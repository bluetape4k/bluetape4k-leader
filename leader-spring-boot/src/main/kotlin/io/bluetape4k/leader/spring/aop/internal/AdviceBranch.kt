package io.bluetape4k.leader.spring.aop.internal

/**
 * `@LeaderElection` / `@LeaderGroupElection` aspect 의 실행 분기.
 *
 * `resolveMetadata` 에서 method signature 를 기반으로 결정되며 `AdviceMetadata` 에 저장됩니다.
 */
internal enum class AdviceBranch {
    /** 일반 blocking 동기 메서드 */
    SYNC,

    /** 마지막 파라미터가 `kotlin.coroutines.Continuation` 인 suspend 메서드 */
    SUSPEND,

    /** 반환 타입이 `reactor.core.publisher.Mono` 인 non-suspend 메서드 */
    MONO,
}
