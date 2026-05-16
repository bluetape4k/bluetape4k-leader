package io.bluetape4k.leader.annotation

/**
 * Protects a method with single-leader election in a distributed environment.
 *
 * ## Contract
 * - The aspect resolves [name], attempts to acquire the leader lock, then runs the method body only
 *   when this node is elected unless [failureMode] says otherwise.
 * - Supported return shapes are `T?`, `suspend T?`, `Mono<T>`, `Flux<T>`, and Kotlin `Flow<T>`.
 * - `Flux<T>` and `Flow<T>` stream returns require either [autoExtend] or [streamBounded].
 * - `@LeaderGroupElection` does not support `Flux<T>` or `Flow<T>` yet.
 *
 * ## SpEL evaluation
 * [name] is plain SpEL. Literal prefixes must be quoted:
 * - `name = "daily-job"` for a static lock name.
 * - `name = "'process-' + #region"` for a prefixed dynamic name.
 * - `name = "#user.tenantId"` for a property lookup.
 * - `name = "\${spring.application.name}-warmup"` after Spring property placeholder resolution.
 * - `name = "process-#region"` is invalid because `process-` is parsed as an identifier.
 *
 * ## Security
 * SpEL method invocation is disabled by default to avoid side-effect expressions such as
 * `#user.delete()`. Set `bluetape4k.leader.aop.spel.allow-method-invocation=true` to opt in.
 *
 * ## Usage
 * ```kotlin
 * @Scheduled(cron = "0 0 2 * * *")
 * @LeaderElection(name = "daily-settlement", leaseTime = "PT1H")
 * fun dailySettlement() { ... }
 *
 * @LeaderElection(name = "'process-' + #region", failureMode = LeaderAspectFailureMode.SKIP)
 * fun process(region: String): Result? = service.process(region)
 *
 * // Explicit factory selection in a multi-backend environment
 * @LeaderElection(name = "audit", bean = "redissonLeaderElectionFactory")
 * fun audit() { ... }
 *
 * @LeaderElection(name = "event-stream", autoExtend = true)
 * fun streamEvents(): Flux<Event> = repository.stream()
 *
 * @LeaderElection(name = "bounded-flow", streamBounded = true)
 * fun boundedFlow(): Flow<Event> = repository.findRecent()
 * ```
 *
 * ## Not-elected mapping
 * - `T?`: returns `null`.
 * - `Unit`: skips the method body.
 * - `Mono<T>`: completes empty.
 * - `Flux<T>` / `Flow<T>`: emits no elements.
 *
 * @property name Required lock name. Plain SpEL plus `${...}` Spring property placeholders.
 * @property waitTime Maximum leader acquisition wait time. Empty means property or core default.
 * @property leaseTime Leader lease duration. Empty means property or core default.
 * @property minLeaseTime Minimum lease retention. `PT0S` releases immediately after fast completion.
 * @property autoExtend Whether to periodically renew the single-leader lease while the action runs.
 * @property streamBounded Opt-in marker that a `Flux` / `Flow` stream completes within the lease window.
 * @property bean [io.bluetape4k.leader.LeaderElectorFactory] bean name to use. Empty means default factory.
 * @property failureMode Backend error handling policy. Defaults to `RETHROW` after property resolution.
 *
 * @see LeaderGroupElection Semaphore-based multi-leader variant.
 * @see LeaderAspectFailureMode Failure mode enum.
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.ANNOTATION_CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class LeaderElection(
    val name: String,
    val waitTime: String = "",
    val leaseTime: String = "",
    val minLeaseTime: String = "PT0S",
    val autoExtend: Boolean = false,
    val streamBounded: Boolean = false,
    val bean: String = "",
    val failureMode: LeaderAspectFailureMode = LeaderAspectFailureMode.INHERIT,
)
