package io.bluetape4k.leader.spring.aop.internal

/** `LeaderElectionAspect`가 대상 메서드의 반환 모델에 따라 사용하는 실행 경로입니다. */
internal enum class AdviceBranch {
    /**
     * 일반 blocking 반환값을 처리하는 경로입니다.
     */
    SYNC,

    /**
     * Kotlin `suspend` 반환값을 처리하는 coroutine 경로입니다.
     */
    COROUTINES,

    /**
     * Reactor `Mono`/`Flux` 또는 Kotlin `Flow` 반환값을 처리하는 reactive 경로입니다.
     */
    REACTIVE,
}
