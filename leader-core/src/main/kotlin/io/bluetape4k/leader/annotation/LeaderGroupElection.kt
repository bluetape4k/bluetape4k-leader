package io.bluetape4k.leader.annotation

/**
 * Annotation that protects a method with multi-leader (semaphore-based) entry in a distributed environment.
 *
 * ## Behavior
 * - On method call, attempts to acquire one of [maxLeaders] slots using [name]. If successful, runs the body;
 *   otherwise branches according to [failureMode].
 * - Supports sync `T?`, `suspend T?`, and Reactor `Mono<T>` return types.
 * - Reactor `Flux<T>` and Kotlin `Flow<T>` return types are intentionally unsupported in 0.3.0.
 *   Group stream execution would require per-slot lease extension semantics across subscription,
 *   cancellation, completion, and error paths. Unsafe stream signatures fail validation and are
 *   also rejected by the runtime aspect at subscription or collection time.
 * - Startup fails if [maxLeaders] ≤ 1 (use [LeaderElection] for single-leader).
 *
 * ## Usage Example
 * ```kotlin
 * @LeaderGroupElection(name = "batch-shard", maxLeaders = 3, leaseTime = "PT5M")
 * fun batch() { ... }
 *
 * @LeaderGroupElection(name = "'process-' + #region", maxLeaders = 2)
 * fun processRegion(region: String) { ... }
 * ```
 *
 * @property name lock name (required). Plain SpEL + `${...}` Spring property placeholder.
 * @property maxLeaders number of concurrent leaders (≥2 required). Startup fails if ≤1.
 * @property waitTime slot acquisition wait time — falls back to property or core default if blank.
 * @property leaseTime slot hold duration — falls back to property or core default if blank.
 * @property minLeaseTime minimum slot hold time. `PT0S` means immediate release on early completion.
 * @property bean [io.bluetape4k.leader.LeaderGroupElectorFactory] bean name to use (literal only).
 * @property failureMode backend exception handling policy. Default is `RETHROW`.
 *
 * @see LeaderElection single-leader variant
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.ANNOTATION_CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class LeaderGroupElection(
    val name: String,
    val maxLeaders: Int = -1,
    val waitTime: String = "",
    val leaseTime: String = "",
    val minLeaseTime: String = "PT0S",
    val bean: String = "",
    val failureMode: LeaderAspectFailureMode = LeaderAspectFailureMode.INHERIT,
)
