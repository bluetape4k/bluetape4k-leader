package io.bluetape4k.leader.spring.aop.internal

/**
 * Execution branch for the `@LeaderElection` / `@LeaderGroupElection` aspect.
 *
 * Determined in `resolveMetadata` based on the method signature and stored in `AdviceMetadata`.
 *
 * ## Naming — category-based (distinct from individual detection markers)
 * - [SYNC]: ordinary blocking synchronous methods
 * - [COROUTINES]: Kotlin coroutine family (currently suspend methods; extensible to Flow in the future)
 * - [REACTIVE]: Reactor / Project Reactor family (currently `Mono`; extensible to `Flux` in the future)
 *
 * Precise per-method detection (`Continuation` parameter / `Mono` return type) is expressed through
 * `AdviceMetadata.isSuspend` / `AdviceMetadata.isMono` properties.
 */
internal enum class AdviceBranch {
    /** Ordinary blocking synchronous method */
    SYNC,

    /** Kotlin coroutine-based (currently suspend method — last parameter is `kotlin.coroutines.Continuation`) */
    COROUTINES,

    /** Reactor-based (currently a non-suspend method returning `reactor.core.publisher.Mono`) */
    REACTIVE,
}
