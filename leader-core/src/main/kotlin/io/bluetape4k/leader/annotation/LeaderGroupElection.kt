package io.bluetape4k.leader.annotation

/**
 * Annotation that protects a method with multi-leader (semaphore-based) entry in a distributed environment.
 *
 * ## Behavior
 * - On method call, attempts to acquire one of [maxLeaders] slots using [name]. If successful, runs the body;
 *   otherwise branches according to [failureMode].
 * - Supports sync `T?` return only. suspend / `Mono<T>` / `Flow<T>` are planned for follow-up [#80].
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
