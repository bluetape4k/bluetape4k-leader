# leader-spring-boot

English | [한국어](README.ko.md)

Spring Boot 4 auto-configuration and AspectJ CTW support for bluetape4k leader election.

---

## Overview

`leader-spring-boot` wires bluetape4k leader backends into Spring applications and provides annotation-based execution guards:

- `@LeaderElection` for a single distributed leader
- `@LeaderGroupElection` for slot-based multi-leader execution
- `@LeaderElectionBackend` for backend selection at method, class, or package level
- Spring Boot auto-configuration for local, Lettuce, Redisson, Exposed JDBC/R2DBC, MongoDB, Hazelcast, and Micrometer integration

The AOP layer is built for AspectJ compile-time weaving via Freefair post-compile weaving. It does not rely on Spring runtime proxy AOP.

## Architecture

![leader spring boot Architecture diagram](../docs/images/readme-diagrams/leader-spring-boot-architecture-01.png)

## Dependency

```kotlin
implementation("io.github.bluetape4k.leader:bluetape4k-leader-spring-boot:0.4.0")

// Add at least one backend module.
implementation("io.github.bluetape4k.leader:bluetape4k-leader-redis-redisson:0.4.0")

// Optional metrics.
implementation("io.github.bluetape4k.leader:bluetape4k-leader-micrometer:0.4.0")
implementation("org.springframework.boot:spring-boot-starter-actuator")

// Optional tracing export, chosen by the application.
// implementation("io.micrometer:micrometer-tracing-bridge-otel")
// implementation("io.opentelemetry:opentelemetry-exporter-otlp")
```

For annotated application methods, enable AspectJ compile-time weaving in the consuming application:

```kotlin
plugins {
    id("io.freefair.aspectj.post-compile-weaving") version "9.5.0"
}
```

## Configuration

```yaml
bluetape4k:
  leader:
    wait-time: 5s
    lease-time: 60s
    group:
      max-leaders: 3
      wait-time: 5s
      lease-time: 60s
    aop:
      enabled: true
      strict: false
      failure-mode: RETHROW
      default-wait-time: 5s
      default-lease-time: 60s
      lock-name-prefix: "${spring.application.name:}:"
      metrics:
        enabled: true
        tags:
          lock-name:
            mode: REDACT
            redacted-value: redacted-lock
          leader-id:
            mode: REDACT
            redacted-value: redacted-leader
          backend-name:
            mode: RAW
      spel:
        allow-method-invocation: false
    diagnostics:
      enabled: true
      strict: false
      include-bean-names: true
    route-guard:
      enabled: false
      authority-mode: STATE
      elector-bean: ""
      rejection-status: SERVICE_UNAVAILABLE
    observability:
      enabled: true
      lock-names:
        - daily-settlement
      health:
        enabled: true
        lease-warning-threshold: 10s
      tracing:
        enabled: true
        include-lock-name: false
        include-leader-id: false
        include-exception-details: false
```

Spring configuration properties use Spring Boot duration binding (`5s`, `60s`, `PT1M`). Core `LeaderElectionOptions` and `LeaderGroupElectionOptions` use `kotlin.time.Duration` in Kotlin code.

## Leader-Gated Routes (0.5.0)

Route guards are opt-in and read-only. They decide whether the local application may serve selected Spring MVC or WebFlux routes; they never acquire, extend, or release a lease on the request path. The feature is inactive unless `bluetape4k.leader.route-guard.enabled=true`.

The default `STATE` authority performs one `LeaderElector.state(slot.lockName)` lookup and allows the request only when the occupied leader state’s audit leader ID equals `slot.leaderId`. Create the leader ID once per live process incarnation, then reuse the same `LeaderSlot` for election and route guarding. Do not reuse a fixed node ID across restarts: a new process could otherwise match a stale lease owned by its predecessor.

```kotlin
@Bean
fun ordersSlot(): LeaderSlot =
    LeaderSlot("orders-route", "orders-node-${UUID.randomUUID()}")

fun runOrdersLeader(elector: LeaderElector, slot: LeaderSlot) =
    elector.runIfLeader(slot) {
        runLeaderWork()
    }
```

Enable the built-in authority and select the elector explicitly when the application has multiple `LeaderElector` beans:

```yaml
bluetape4k:
  leader:
    route-guard:
      enabled: true
      authority-mode: STATE
      elector-bean: ordersLeaderElector
      rejection-status: SERVICE_UNAVAILABLE
```

Register the generated MVC interceptor only for protected paths:

```kotlin
@Configuration
class OrdersMvcRoutes(
    private val guards: LeaderMvcRouteGuardFactory,
    private val ordersSlot: LeaderSlot,
) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(guards.interceptor(ordersSlot))
            .addPathPatterns("/internal/orders/**")
    }
}
```

`STATE` starts only when the selected elector declares `supportsAuditLeaderState=true`. The built-in Local, Consul, DynamoDB, and Kubernetes Lease electors provide this audit-identity state capability; their listening, tenant-scoped, and Micrometer decorators preserve it. Electors that inherit the empty `state()` fallback, including Lettuce and Redisson, fail startup with `LEADER_ROUTE_ELECTOR_STATE_UNSUPPORTED`; use explicit `CUSTOM` mode for those backends unless the application supplies another trustworthy ownership source.

For WebFlux, apply the generated filter only inside the application's route/path selection. A `WebFilter` bean is global, so do not register the returned filter as an unrestricted bean when only some routes are leader-gated:

```kotlin
@Bean
fun ordersRouteGuard(
    guards: LeaderWebFluxRouteGuardFactory,
    ordersSlot: LeaderSlot,
): WebFilter {
    val guarded = guards.filter(ordersSlot)
    val path = PathPatternParser().parse("/internal/orders/**")
    return WebFilter { exchange, chain ->
        if (path.matches(exchange.request.path.pathWithinApplication())) {
            guarded.filter(exchange, chain)
        } else {
            chain.filter(exchange)
        }
    }
}
```

Applications that need another authority source may choose `CUSTOM` and provide exactly one `LeaderRouteAuthority` bean:

```yaml
bluetape4k:
  leader:
    route-guard:
      enabled: true
      authority-mode: CUSTOM
      rejection-status: LOCKED
```

```kotlin
@Bean
fun controlPlaneAuthority(controlPlane: ControlPlane): LeaderRouteAuthority =
    LeaderRouteAuthority { slot ->
        if (controlPlane.isLocalLeader(slot)) {
            LeaderRouteDecision.Allowed
        } else {
            LeaderRouteDecision.NotLeader
        }
    }
```

`STATE` and `CUSTOM` are strictly separate modes. `STATE` rejects every application-provided `LeaderRouteAuthority`; `CUSTOM` requires exactly one such bean and rejects a configured `elector-bean`. Missing, ambiguous, wrong-type, or state-incapable electors and invalid authority combinations fail startup with stable codes: `LEADER_ROUTE_AUTHORITY_MIXED`, `LEADER_ROUTE_AUTHORITY_MISSING`, `LEADER_ROUTE_AUTHORITY_AMBIGUOUS`, `LEADER_ROUTE_ELECTOR_MISSING`, `LEADER_ROUTE_ELECTOR_AMBIGUOUS`, or `LEADER_ROUTE_ELECTOR_STATE_UNSUPPORTED`.

Custom authorities must be bounded, side-effect-free, and must not acquire, extend, or release leases. Ordinary authority/state failures fail closed. The supported rejection statuses are `NOT_FOUND` (404), `CONFLICT` (409), `LOCKED` (423), and `SERVICE_UNAVAILABLE` (503, default). Rejections have an empty body and expose no leader identity, lock name, backend error, host, or `Location` header.

The built-in state decision is best-effort leader state, not an atomic guarantee that leadership lasts for the whole HTTP request. Use it only where a short stale-state window is acceptable; use `@LeaderElection` or an explicit lease-owned execution path when the work itself must be protected by a lease.

### Redirect-to-leader (opt-in)

Redirects are a separate, disabled-by-default policy. The application owns the
mapping from a `LeaderRouteRedirectContext` to a public URI; the library never
turns `leaderId`, `nodeId`, backend addresses, or backend errors into a URL.
Only a validated `307 Temporary Redirect` is emitted, and all other cases keep
the configured empty-body rejection response.

```yaml
bluetape4k:
  leader:
    route-guard:
      enabled: true
      authority-mode: STATE
      redirect:
        enabled: true
        allowed-hosts: [leader.example]
        trusted-proxy-addresses: [10.0.0.10]
        lease-safety-window: 250ms
```

The resolver is synchronous, immutable, bounded, and application-owned. A
relative target such as `/leader/orders` can use the resolver-only overload.
Absolute targets require the exact lowercase HTTPS host in `allowed-hosts` and
raw request metadata captured before forwarded-header transformation:

```kotlin
val resolver = LeaderRouteRedirectResolver { context ->
    context.leaderState?.leader?.auditLeaderId?.let(publicLeaderRoutes::lookup)?.uri
}

val metadataProvider = LeaderRouteRedirectRequestMetadataProvider<HttpServletRequest> { request ->
    LeaderRouteRedirectRequestMetadata(
        forwardedHeadersPresent = request.getAttribute("raw.forwarded.present") as Boolean?,
        transportPeerAddress = request.getAttribute("raw.transport.peer") as String?,
    )
}

registry.addInterceptor(guards.interceptor(ordersSlot, resolver, metadataProvider))
    .addPathPatterns("/internal/orders/**")
```

`forwardedHeadersPresent = null` permits only relative targets. `true` requires
an exact numeric transport-peer match in `trusted-proxy-addresses`; the library
does not parse forwarded headers or infer trust from a transformed remote
address. Relative paths cannot be network-path references, fragments, controls,
userinfo, or backslash forms. Absolute targets are HTTPS, implicit port 443,
ASCII exact-host matches only. A missing/expired lease, unavailable authority,
resolver or metadata-provider exception, or unsafe URI fails closed without
changing election state.

For WebFlux, capture raw metadata at the pre-transform server/`HttpHandler`
boundary (or another trusted application boundary), then pass it through the
same resolver overload. Ordinary `WebFilter` ordering is not a pre-transform
guarantee. If that boundary cannot be established, use the resolver-only
overload with a relative URI and omit request metadata. Parse `PathPattern` once
outside the request lambda when applying a guard to selected paths.

## Leader Readiness (0.5.0)

The opt-in `leaderElectionReadiness` contributor checks only lock names configured in or observed by this JVM. It performs one read-only `LeaderElector.state(...)` call per known name; it never enumerates backend locks or changes election state.

```yaml
bluetape4k:
  leader:
    observability:
      lock-names: [daily-settlement]
      health:
        enabled: true
        lease-warning-threshold: 10s

management:
  endpoint:
    health:
      group:
        readiness:
          include: readinessState,leaderElectionReadiness
```

The contributor reports `UP` for successful reads without near-expiry leases, `OUT_OF_SERVICE` when an occupied lease expires within the threshold, and `DOWN` when a known-lock state read fails. Unknown lease expiry is reported in details but does not make the application unready. The result is a best-effort JVM-local diagnostic, not an ownership decision.

Each health evaluation performs one backend state read per lock name in the JVM-local registry, so its cost grows linearly with the registered lock count and backend latency. Enable it for a small, bounded set of stable lock names; leave it disabled for applications that generate unbounded dynamic names. Health details can include raw lock names, so protect Actuator access and configure `management.endpoint.health.show-details` according to your disclosure policy.

### Recent acquisition failures

The readiness contributor also exposes a best-effort aggregate of recent AOP acquisition failures. Configure the observation window with a positive finite duration; the default is `5m` and the retained window has a fixed capacity of `1024` timestamps:

```yaml
bluetape4k:
  leader:
    observability:
      health:
        acquisition-failure-window: 5m
```

Only `LeaderAopMetricsRecorder.onLockNotAcquired(..., SkipReason.BACKEND_ERROR)` enters this aggregate. Normal `CONTENTION` skips and `FAIL_OPEN_FORCED` skips are excluded. `recentAcquisitionFailures` is the number retained in the current window; when the fixed capacity is exceeded, `acquisitionFailureWindowOverflowed=true` marks the count as a lower bound. After the window expires, `lastAcquisitionFailureAt` becomes `null`. The detail contains no lock name or exception message.

Recent failures alone never change readiness from `UP`, `OUT_OF_SERVICE`, `DOWN`, or `UNKNOWN`; the window is an observation aid, not a readiness decision. Keep the Actuator endpoint protected, and keep the dynamic lock-name registry small and bounded because the readiness contributor still performs one backend read per registered name.

## Startup Diagnostics

`LeaderStartupDiagnosticsAutoConfiguration` runs after backend, observability, and Actuator auto-configuration. It records a startup report with the selected backend candidates, `LeaderElector` bean count, `leaderElection` endpoint enablement, web exposure state, and warnings for risky combinations.

Diagnostics are non-fatal by default. Set `bluetape4k.leader.diagnostics.strict=true` to fail startup when a warning is found. This is separate from `bluetape4k.leader.aop.strict`: AOP strict mode validates annotated methods, while diagnostics strict mode validates the assembled Spring context and management/cardinality settings.

| Warning | Meaning | Typical fix |
|---|---|---|
| `MULTIPLE_NON_LOCAL_BACKENDS` | More than one non-local `LeaderElector` is active. | Select a bean with `@LeaderElection(bean = "...")`, `@LeaderElectionBackend`, or `@Primary`. |
| `MANAGEMENT_ENDPOINT_NOT_EXPOSED` | `management.endpoint.leaderElection.enabled=true`, but web exposure does not include `leaderElection` or `*`. | Add `leaderElection` to `management.endpoints.web.exposure.include`. |
| `MANAGEMENT_REGISTRY_NOT_SEEDED` | The endpoint is enabled but `bluetape4k.leader.observability.lock-names` is empty, so the initial report can look empty until runtime events arrive. | Seed static lock names for scheduled jobs or accept runtime discovery. |
| `RAW_LOCK_NAME_TAGS` | Raw `lock.name` metric tags are enabled without an allow-list. | Keep `REDACT`, or use a small allow-list, `HASH`, or `TRUNCATE`. |
| `RAW_LEADER_ID_TAGS` | Opt-in raw `leader.id` Observation tags can be emitted without an allow-list. | Disable leader ID tags, or bound them with tag policy. |

The `leaderElection` Actuator endpoint currently exposes read-only status operations. Diagnostics therefore checks endpoint visibility and tag-cardinality risks, not destructive management actions.

## Metrics And Observation Tracing

`LeaderMicrometerAutoConfiguration` registers `MicrometerLeaderAopMetricsRecorder` when `leader-micrometer` and a `MeterRegistry` bean are present. `LeaderObservationAutoConfiguration` registers `MicrometerObservationLeaderAopMetricsRecorder` and `MicrometerObservationLeaderElectionListener` when `leader-micrometer` and an `ObservationRegistry` bean are present.

![leader metrics and Observation tracing bridge architecture](../docs/images/readme-diagrams/leader-micrometer-architecture-01.png)

Metrics and Observations are independent:

- disabling `bluetape4k.leader.aop.metrics.enabled` disables the existing meter recorder only;
- disabling `bluetape4k.leader.observability.tracing.enabled` disables only the Observation bridge;
- disabling `bluetape4k.leader.observability.enabled` disables the tracing bridge together with leader observability support beans.

| Property | Default | Controls |
|---|---:|---|
| `bluetape4k.leader.aop.metrics.enabled` | `true` | Existing Micrometer meter recorder |
| `bluetape4k.leader.aop.metrics.tags.lock-name.mode` | `REDACT` | Export policy for meter `lock.name` tags |
| `bluetape4k.leader.aop.metrics.tags.lock-name.redacted-value` | `redacted-lock` | Sentinel for redacted lock names |
| `bluetape4k.leader.aop.metrics.tags.leader-id.mode` | `REDACT` | Export policy for opt-in Observation `leader.id` values |
| `bluetape4k.leader.aop.metrics.tags.backend-name.mode` | `RAW` | Export policy for bounded backend labels when custom or future meter paths emit `backend.name`; current built-in meters do not emit it |
| `bluetape4k.leader.observability.enabled` | `true` | Parent switch for leader observability and tracing |
| `bluetape4k.leader.observability.health.acquisition-failure-window` | `5m` | Bounded window for aggregate AOP backend acquisition failures |
| `bluetape4k.leader.observability.tracing.enabled` | `true` | Observation recorder and listener |
| `bluetape4k.leader.observability.tracing.include-lock-name` | `false` | Opt-in `lock.name` high-cardinality Observation data, sanitized by tag policy |
| `bluetape4k.leader.observability.tracing.include-leader-id` | `false` | Opt-in `leader.id` high-cardinality Observation data when identified context exists, sanitized by tag policy |
| `bluetape4k.leader.observability.tracing.include-exception-details` | `false` | Raw throwable details through `Observation.error(...)` |

The Observation bridge emits standalone terminal observations such as `leader.aop.acquire`, `leader.aop.execution`, and `leader.election.event`. It does not open a new current `Observation.Scope` around the protected method body.

#529 emits Micrometer Observations only. Applications must add their own Micrometer tracing bridge, exporter, collector, and OpenTelemetry SDK if they want exported traces.

Dynamic lock names, leader IDs, and exception details are production-sensitive. They can contain tenant, user, job, URL, or credential-like data. Metrics now redact `lock.name` by default; opt into `RAW` only for small static job sets, or use `HASH`/`TRUNCATE` through `bluetape4k.leader.aop.metrics.tags.*` when dashboards need bounded correlation. Current Spring AOP does not synthesize `leader.id` from node IDs or lock names; `include-leader-id=true` emits a value only when the recorder receives `LeaderAopMetricsContext.Identified` from direct or future identity-aware paths.

Lease-extension observations are deferred to issue #559 because `LockExtender` needs a core hook before Spring or Micrometer can observe extension outcomes.

## Backend Factories

`LeaderAopFactoryAutoConfiguration` registers factory beans when the matching backend client is present.

| Backend | Required bean | Factory bean examples |
|---------|---------------|-----------------------|
| Local | none | `localLeaderElectionFactory`, `localSuspendLeaderElectorFactory` |
| Lettuce | `StatefulRedisConnection<String, String>` | `lettuceLeaderElectionFactory`, `lettuceSuspendLeaderElectorFactory` |
| Redisson | `RedissonClient` | `redissonLeaderElectionFactory`, `redissonSuspendLeaderElectorFactory` |
| Exposed JDBC | `Database` | `exposedJdbcLeaderElectionFactory` |
| Exposed R2DBC | `R2dbcDatabase` | `exposedR2dbcSuspendLeaderElectorFactory` |
| MongoDB | `MongoClient` | `mongoLeaderElectionFactory`, `mongoSuspendLeaderElectorFactory` |
| Hazelcast | `HazelcastInstance` | `hazelcastLeaderElectionFactory` |

Use `bean = "..."` on the annotation when more than one backend is available.

## Annotation Usage

```kotlin
@Service
class SettlementJobs {
    @LeaderScheduled(
        name = "daily-settlement",
        cron = "\${jobs.settlement.cron:0 0 2 * * *}",
        leaseTime = "30m",
        minLeaseTime = "10s",
    )
    fun settleDaily(): SettlementReport? =
        settlementService.settle()

    @LeaderGroupElection(name = "'region-sync-' + #region", maxLeaders = 3)
    fun syncRegion(region: String) {
        syncService.sync(region)
    }
}
```

`@LeaderScheduled` composes Spring `@Scheduled` with `@LeaderElection`; Spring still owns scheduling and scheduled-task observation, while the existing leader aspect owns lock acquisition and contention skips. Spring scheduling must be enabled, and the usual `@Scheduled` method-signature and exactly-one-trigger rules still apply. Separate `@Scheduled` and `@LeaderElection` annotations remain useful for custom composed annotations or when the two concerns should stay visually explicit.

### YAML-only policy for existing scheduled methods

When changing an existing scheduled method is not practical, enable the opt-in
property policy and select the method by its exact Spring bean name and method
name:

```yaml
bluetape4k:
  leader:
    scheduling:
      enabled: true
      policies:
        - selector: "orderJob#reconcile"
          name: "orders:reconcile"
          wait-time: 0s
          lease-time: 30s
          min-lease-time: 5s
          bean: "redisLeaderElectionFactory"
          auto-extend: false
          stream-bounded: false
          failure-mode: SKIP
```

The default is `enabled: false`. Selectors are exact `beanName#methodName`
values; wildcards, regular expressions, whitespace, and overloaded method
names are rejected at startup. Use an explicit, stable Spring bean name and
`bean` factory name when more than one backend is available. A blank or
unmatched selector, invalid duration or SpEL expression, unresolved backend,
or an invalid stream policy fails startup before the scheduled task can run.

Precedence is explicit annotation (`@LeaderElection` or `@LeaderScheduled`),
then the matching property policy, then no leader metadata. With no metadata,
the existing `@Scheduled` method proceeds unchanged. `failure-mode: SKIP`
preserves normal contention behavior: the scheduled body is not invoked and no
contention exception is thrown. `Flux` and Kotlin `Flow` methods still require
`auto-extend: true` or `stream-bounded: true`.

Spring continues to own the scheduled task, trigger, subscription, context
close, and task `Observation` lifecycle; the policy registry stores metadata
only. Policies are startup-only: dynamic reload and wildcard matching are not
supported. To roll back, set `bluetape4k.leader.scheduling.enabled=false`; the
normal Spring scheduler path remains in place.

### Sequence: AOP-triggered `runIfLeader`

![Sequence: AOP-triggered runIfLeader diagram](../docs/images/readme-diagrams/leader-spring-boot-sequence-01.png)

Supported return shapes:

| Shape | Behavior |
|-------|----------|
| `T?` / `Unit` | Runs on the leader and returns the body result, or skips with `null` / no-op |
| `suspend fun` | Uses `SuspendLeaderElectorFactory` and propagates `LeaderElectionInfo` in `CoroutineContext` |
| `Mono<T>` | Uses Reactor context propagation for `LeaderElectionInfo` |
| `Flux<T>` / `Flow<T>` | Tracked separately in issue #74 because long-lived streams require lease renewal |

## SpEL Lock Names

`name` supports static names, Spring placeholders, plain SpEL, and template SpEL.

```kotlin
@LeaderElection(name = "daily-report")
fun dailyReport() = report()

@LeaderElection(name = "'tenant-' + #tenantId + '-invoice'")
fun invoice(tenantId: String) = invoiceService.run(tenantId)

@LeaderElection(name = "job-#{#region}-${spring.application.name}")
fun regionalJob(region: String) = jobService.run(region)
```

Method invocation in SpEL is disabled by default. Enable it only for trusted expressions:

```yaml
bluetape4k.leader.aop.spel.allow-method-invocation: true
```

## Meta-Annotations

`@LeaderElection` and `@LeaderGroupElection` can be composed with Spring `@AliasFor`.

```kotlin
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@LeaderElection(name = "", leaseTime = "5m")
annotation class DailyLeaderJob(
    @get:AliasFor(annotation = LeaderElection::class, attribute = "name")
    val name: String,
)
```

Backend selection can also be lifted to method, class, or package level:

```kotlin
@LeaderElectionBackend("redissonLeaderElectionFactory")
class RedisBackedJobs {
    @LeaderElection(name = "daily-report")
    fun report() = reportService.run()
}
```

## Failure Modes

| Mode | Behavior |
|------|----------|
| `RETHROW` | Wrap backend failures in `LeaderElectionException` / `LeaderGroupElectionException` |
| `SKIP` | Treat backend failure or contention as skipped execution |
| `FAIL_OPEN_RUN` | Run the method body without a lock when the backend is unavailable or the lock is not acquired |
| `INHERIT` | Annotation sentinel; uses `bluetape4k.leader.aop.failure-mode` |

`FAIL_OPEN_RUN` is only appropriate for idempotent work because multiple nodes may execute the body concurrently.

## LockAssert & LockExtender (ShedLock-equivalent — issue #79)

`leader-core` ships ShedLock-style ergonomic APIs that you can call from within `@LeaderElection` / `@LeaderGroupElection` bodies:

```kotlin
@Service
class ReportJobs {
    @LeaderElection(name = "daily-report", leaseTime = "30m", minLeaseTime = "10s")
    fun runReport(): Report? {
        LockAssert.assertLocked()    // throws IllegalStateException if not inside an active leader scope
        // ... critical work ...
        if (needsExtraTime) {
            LockExtender.extendActiveLock(60.seconds)    // returns true on success
        }
        return reportService.generate()
    }
}
```

### Lock identity

Reentrant `@LeaderElection` calls (same `name`, same JVM, same thread/coroutine) are detected by **`LockIdentity` (lockName + annotation kind + group params)** — the backend is acquired exactly once. `factoryBeanName` is intentionally excluded from equality so that sync ↔ suspend nested calls work correctly (Step 3-P R3).

### Suspend / Mono

Inside `suspend` and `Mono`-returning bodies, the lock handle propagates via `CoroutineContext` (no `ThreadLocal` fallback). Use the suspend variants:

```kotlin
@LeaderElection(name = "stream-job")
suspend fun stream(): Result? {
    LockAssert.assertLockedSuspend()
    LockExtender.extendActiveLockSuspend(2.minutes)
    return streamService.process()
}
```

⚠️ **Reactor non-suspend operators (`.map`, `.filter`) are unsupported.** Call `LockAssert.assertLockedSuspend()` inside `.flatMap { mono { ... } }`:

```kotlin
@LeaderElection(name = "mono-job")
fun process(): Mono<String> =
    sourceMono
        .flatMap { value ->
            mono {
                LockAssert.assertLockedSuspend()    // ✅
                transform(value)
            }
        }
```

### Sequence: reentrant `@LeaderElection`

![Sequence: reentrant @LeaderElection diagram](../docs/images/readme-diagrams/leader-spring-boot-sequence-02.png)

### Watchdog × LockExtender

Both share the **same `ExtendDelegate` reference** (atomicity guaranteed by token-guarded backend operations). When you call `LockExtender.extendActiveLock(d)`, the delegate records `now + d` in `lastExtendDeadline` so the next watchdog tick will skip backend re-extend if the user-provided deadline is larger. For strict deadline semantics (ShedLock parity), turn off watchdog.

### Return values

| API | Outside scope | Inside `Real` | Inside `FailOpen` sentinel |
|---|---|---|---|
| `LockAssert.assertLocked()` | throws `IllegalStateException` | passes | throws |
| `LockAssert.isLocked()` | `false` | `true` | `false` |
| `LockExtender.extendActiveLock(d)` | `false` + WARN | backend result | `false` + WARN |
| `LockExtender.extendActiveLockDetailed(d)` | `NotHeld` | `Extended` / `NotHeld` / `WrongThread` / `BackendError` | `NotHeld` |

For Java callers, `@JvmStatic` overloads accept both `kotlin.time.Duration` and `java.time.Duration`.

## Auto-Configuration Order

1. `LeaderElectionAutoConfiguration` binds shared backend properties.
2. `LeaderAopFactoryAutoConfiguration` registers backend factories.
3. `LeaderMicrometerAutoConfiguration` registers `MicrometerLeaderAopMetricsRecorder` when `MeterRegistry` exists.
4. `LeaderObservationAutoConfiguration` registers Observation recorder/listener beans when `ObservationRegistry` exists.
5. `LeaderAcquisitionFailureWindowAutoConfiguration` registers the bounded backend-failure recorder before AOP execution.
6. `LeaderAopAutoConfiguration` registers the Aspect, SpEL evaluator, lock-name validator, and annotation validator.
7. `LeaderMicrometerHealthAutoConfiguration` registers the Actuator health indicator when Actuator is present.
8. `LeaderElectionObservabilityAutoConfiguration` registers the lock-name status registry and fallback event-publisher adapter.
9. `LeaderElectionActuatorAutoConfiguration` registers the opt-in `/actuator/leaderElection` endpoint.
10. `LeaderBackendDiagnosticsActuatorAutoConfiguration` registers the opt-in static `/actuator/leaderBackendDiagnostics` endpoint.
11. `LeaderBackendHealthAutoConfiguration` registers the opt-in backend connectivity health indicator.
12. `LeaderStartupDiagnosticsAutoConfiguration` records backend, management, and cardinality diagnostics after the runtime surface exists.

## Leader Election Actuator Endpoint

The `leaderElection` endpoint is disabled by default. Enable the endpoint and expose it over HTTP explicitly:

```yaml
bluetape4k:
  leader:
    observability:
      lock-names:
        - batch-job
        - migration-gate

management:
  endpoint:
    leaderElection:
      enabled: true
  endpoints:
    web:
      exposure:
        include: health,leaderElection
```

```http
GET /actuator/leaderElection
```

```json
{
  "locks": [
    {
      "name": "batch-job",
      "status": "Occupied",
      "leaderId": "node-1",
      "leaseExpiry": "2026-05-16T00:00:00Z"
    }
  ],
  "acquisitionFailures": {
    "count": 0,
    "lastFailureAt": null,
    "window": "PT5M",
    "capacity": 1024,
    "overflowed": false
  }
}
```

`lock-names` seeds the JVM-local status registry before the first runtime event. Listener-aware electors can also add names as they observe lifecycle events. The fallback `LeaderElectionEventPublisher` is publisher-only and never becomes a `LeaderElector` candidate, so existing elector injection remains stable.

`acquisitionFailures` is the same bounded aggregate used by readiness. It contains timestamps and counts only: it never exposes a lock name or backend exception message. The endpoint is read-only but may still reveal operational failure volume, so expose it only to trusted Actuator clients.

## Backend Diagnostics And Connectivity Health

The static backend diagnostics endpoint is disabled by default. It reports the selected backend descriptor without performing network or database I/O, so its connectivity status is `NOT_CHECKED`.

```yaml
management:
  endpoint:
    leaderBackendDiagnostics:
      enabled: true
  endpoints:
    web:
      exposure:
        include: health,leaderElection,leaderBackendDiagnostics
```

```http
GET /actuator/leaderBackendDiagnostics
```

Enable the separate connectivity health indicator only when an active backend probe is appropriate for your deployment:

```yaml
bluetape4k:
  leader:
    observability:
      backend-health:
        enabled: true
        timeout: 500ms
```

`UP` and `DOWN` map directly to Spring health statuses. `UNKNOWN` and `NOT_CHECKED` map to Spring `UNKNOWN`. Both surfaces use the same elector selection as `bluetape4k.leader.observability.state-provider-bean`; when the selected elector does not expose a `LeaderBackendDiagnosticsProvider`, the typed endpoint and health indicator are not registered.

Protect these Actuator surfaces with the same authentication and network policy as other management endpoints. A healthy backend only proves connectivity at the time of the probe; it does not prove that this process currently owns a leader lease.

## Migration Notes

- Core option constructors use `kotlin.time.Duration`: `LeaderElectionOptions(waitTime = 5.seconds, leaseTime = 60.seconds)`.
- Spring property classes still use Spring Boot duration binding, so YAML values such as `5s`, `60s`, and `PT1M` remain valid.
- Bean names use `LeaderElector` terminology. Prefer names such as `redissonLeaderElectionFactory` and `localSuspendLeaderElectorFactory`; avoid older `LeaderElection` bean names.

## Testing

Use `ApplicationContextRunner` for auto-configuration tests and keep infrastructure-backed tests on the singleton servers from `bluetape4k-testcontainers`. The module keeps a lower Kover threshold because AspectJ CTW and Spring Boot integration are verified by targeted integration tests.
