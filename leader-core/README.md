# leader-core

English | [한국어](README.ko.md)

Core interfaces and local in-process implementations for `bluetape4k-leader`.

---

## Overview

`leader-core` defines the contracts for all leader election backends and provides local (in-process) implementations that need no external infrastructure. Use local implementations in single-instance deployments or tests.

## Architecture

![leader-core API contract map](../docs/images/readme-diagrams/leader-core-class-01.png)

## API Contract

### `runIfLeader(lockName, action): T?`

- Acquires the named lock (or semaphore slot for group elections)
- If acquired: executes `action` and returns its result
- If not acquired within `waitTime`: returns **`null`** (never throws on contention)
- Exceptions from `action` are propagated to the caller
- Lock is released after `action` completes (or on exception)

### `runIfLeaderResult*`: explicit execution outcome

Use result APIs when `null` is a valid action value or when callers need to distinguish contention from
action failure:

```kotlin
when (val result = election.runIfLeaderResult("daily-job") { computeOrNull() }) {
    is LeaderRunResult.Elected -> use(result.value)       // action ran; value may be null
    LeaderRunResult.Skipped -> recordContention()         // action did not run
    is LeaderRunResult.ActionFailed -> report(result.cause)
}
```

`LeaderRunResult` has three states:

- `Elected(value, leaderId?)`: lock or slot was acquired and the action completed.
- `Skipped`: lock or slot was not acquired, so the action was not executed.
- `ActionFailed(cause)`: lock or slot was acquired and the action started, but it failed.

Result APIs never convert `CancellationException` into `ActionFailed`. Blocking and coroutine APIs rethrow it;
async and virtual-thread APIs complete exceptionally instead (for `join()`, expect `CompletionException`
wrapping the cancellation; `isCancelled()` is not guaranteed). Blocking APIs also rethrow
`InterruptedException` after restoring the interrupt flag.

### Election lifecycle listeners

`LeaderElectionListenerRegistry` implementations support `addListener` and `removeListener` for lifecycle callbacks:

- `onElected(lockName)` before the guarded action starts
- `onElected(lockName, leader)` for implementations that need best-effort owner and lease expiry metadata
- `onRevoked(lockName)` after the held lock or slot is released by the current call
- `onSkipped(lockName)` when the action is not run because leadership was not acquired

`LeaderElectionEventPublisher.events` exposes the same lifecycle as a hot `Flow<LeaderElectionEvent>`.
`LeaderElectionEvent.Elected` carries the same optional `LeaderLease` state. `leader.leaseUntil` and
`leaseExpiry` are `null` when a backend cannot report a precise expiry; treat them as observability metadata,
not as an ownership decision.

For framework integrations and Java-friendly adapters, register callbacks directly on the publisher. The caller
owns the `CoroutineScope`; closing the returned handle cancels only that callback collection.

```kotlin
val election = LocalLeaderElector()
val handle = election.addListener(object : LeaderElectionListener {
    override fun onElected(lockName: String, leader: LeaderLease?) {
        println("elected: $lockName until ${leader?.leaseUntil ?: "unknown"}")
    }
})

try {
    election.runIfLeader("daily-job") { processData() }
} finally {
    handle.close()
}
```

```kotlin
val election = LocalSuspendLeaderElector()

val handle = election.onElected(applicationScope) { event ->
    println("elected: ${event.lockName} by ${event.leaderId ?: "unknown"}")
}

election.runIfLeader("nightly-sync") { syncToRemote() }
handle.close()
```

### Audit export and HTTP/webhook delivery

`LeaderAuditExporter` sends already-sanitized history or lifecycle events through a
bounded, asynchronous pipeline. `submit` only reports admission: `ACCEPTED` does not
mean that the receiver has accepted the request. Close the exporter before shutting
down the executor or scheduler supplied in `LeaderAuditExportOptions`.

The JDK adapter keeps serialization in the application and accepts only an explicitly
trusted HTTPS endpoint. Redirects are disabled, response bodies are discarded, and the
only request headers allowed by the adapter are `Content-Type` and `Authorization`.
The endpoint wrapper is a syntax and responsibility boundary; DNS, SSRF, private-network,
and DNS-rebinding policy remains with the caller or its egress proxy.

```kotlin
val scheduler = Executors.newSingleThreadScheduledExecutor()
val executor = Executors.newVirtualThreadPerTaskExecutor()
val endpoint = LeaderAuditTrustedHttpsEndpoint.trusted(
    URI("https://audit.example.test/v1/leader-events"),
)
val client = HttpClient.newBuilder()
    .followRedirects(HttpClient.Redirect.NEVER)
    .build()
val exporter = HttpLeaderAuditExporter(
    client = client,
    endpoint = endpoint,
    headers = mapOf("Authorization" to "Bearer ${System.getenv("AUDIT_WEBHOOK_TOKEN")}"),
    encoder = LeaderAuditPayloadEncoder { event ->
        LeaderAuditHttpPayload.of(
            contentType = "text/plain; charset=utf-8",
            body = event.toString().toByteArray(),
        )
    },
    exportOptions = LeaderAuditExportOptions(
        queueCapacity = 256,
        maxInFlight = 8,
        maxAttempts = 3,
        attemptTimeout = Duration.ofSeconds(5),
        initialBackoff = Duration.ofMillis(100),
        maxBackoff = Duration.ofSeconds(5),
        executor = executor,
        scheduler = scheduler,
    ),
    httpOptions = LeaderAuditHttpOptions.defaults(),
)

try {
    exporter.submit(event)
} finally {
    exporter.close()
    executor.close()
    scheduler.shutdown()
}
```

Use a real serializer in the injected `LeaderAuditPayloadEncoder` when the receiver
expects JSON. JSONL files and OpenTelemetry exporters are separate transports and are
not added by `leader-core`. A receiver should also provide idempotency for retries and
should treat delivery attempts as at-least-once.

### Options

```kotlin
LeaderElectionOptions(
    waitTime: Duration = 5.seconds,   // max wait for lock acquisition
    leaseTime: Duration = 60.seconds, // max lock hold time
    minLeaseTime: Duration = Duration.ZERO, // minimum local hold time
    autoExtend: Boolean = false // renew a single-leader lease while action runs
)

LeaderGroupElectionOptions(
    maxLeaders: Int = 2,                          // max concurrent leaders
    waitTime: Duration = 5.seconds,
    leaseTime: Duration = 60.seconds,
    minLeaseTime: Duration = Duration.ZERO,
    useDbTime: Boolean = false                    // Exposed JDBC/R2DBC group ownership only
)
```

`useDbTime` is consumed by the Exposed JDBC/R2DBC group electors. Other
backends keep their existing clock behavior.

`minLeaseTime` is the lockAtLeastFor equivalent. Local electors keep the lock or slot until the minimum hold time has elapsed. Supported distributed backends delegate the remaining minimum lease to their storage TTL on release.

`autoExtend` is a single-leader option. Local electors keep mutual exclusion with the JVM lock and refresh state snapshots while distributed backends implement owner-conditional lease renewal.

## Sequence Diagrams

### Single-leader: lock acquire/release

![Single-leader runIfLeader flow](../docs/images/readme-diagrams/leader-core-sequence-02.png)

### Multi-leader group: slot-based semaphore (maxLeaders = N)

![Group-leader slot flow](../docs/images/readme-diagrams/leader-core-sequence-03.png)

## Local Implementations

All local implementations use JVM primitives (`ReentrantLock`, `Semaphore`) — no external dependencies.

| Class | Interface | Description |
|-------|-----------|-------------|
| `LocalLeaderElector` | `LeaderElector` | Blocking, `ReentrantLock`-based |
| `LocalAsyncLeaderElector` | `AsyncLeaderElector` | `CompletableFuture` on thread pool |
| `LocalVirtualThreadLeaderElector` | `VirtualThreadLeaderElector` | Virtual thread per election |
| `LocalSuspendLeaderElector` | `SuspendLeaderElector` | Coroutine with `Mutex` |
| `LocalLeaderGroupElector` | `LeaderGroupElector` | `Semaphore`-based multi-leader |
| `LocalSuspendLeaderGroupElector` | `SuspendLeaderGroupElector` | Coroutine `Semaphore` |
| `LocalStrategicLeaderElector` | `StrategicLeaderElector` | Strategy-based blocking election |
| `LocalStrategicSuspendLeaderElector` | `StrategicSuspendLeaderElector` | Strategy-based coroutine election |

## Strategic Election

### Overview

Strategic election separates the **nomination phase** (candidate registration) from the **decision phase** (strategy application), enabling flexible leader selection policies.

```
registerCandidate() → elect(strategy) → 1 winner, rest skipped
```

### Built-in Strategies

| Strategy | Description |
|----------|-------------|
| `FifoElectionStrategy` | Earliest registered candidate wins |
| `RandomElectionStrategy(seed)` | Deterministic random selection (seed required for distributed use) |
| `ScoredElectionStrategy(scorer)` | Highest-scoring candidate wins |

### Built-in Scorers (0–100 normalized)

| Scorer | Description |
|--------|-------------|
| `IdleTimeScorer` | Node idle longest since last completion |
| `SuccessRateScorer` | Highest success-rate node |
| `RecentSuccessScorer` | Most recently succeeded node |
| `WeightedScorer` | Weighted sum of multiple scorers |

### Key Interfaces

```kotlin
interface StrategicLeaderElector {
    val nodeId: String
    fun registerCandidate(lockName: String, info: CandidateInfo, ttl: Duration = Duration.ZERO)
    fun unregisterCandidate(lockName: String, nodeId: String)
    fun listCandidates(lockName: String): List<CandidateInfo>
    fun <T> runIfLeader(lockName: String, strategy: ElectionStrategy, options: LeaderElectionOptions, action: () -> T): T?
}
```

## Usage Examples

### Strategic election — scored idle-time

```kotlin
val election = LocalStrategicLeaderElector("node-1")

election.registerCandidate("batch-job", CandidateInfo("node-1"))
election.registerCandidate("batch-job", CandidateInfo("node-2"))

val result = election.runIfLeader("batch-job", ScoredElectionStrategy(IdleTimeScorer)) {
    processBatch()
}
// Only the node idle longest runs processBatch(); others return null
```

### Strategic election — weighted scorer

```kotlin
val scorer = WeightedScorer(IdleTimeScorer to 0.4, SuccessRateScorer to 0.6)
val strategy = ScoredElectionStrategy(scorer)

val result = election.runIfLeader("weighted-job", strategy) { work() }
```

### Blocking single-leader

```kotlin
val election = LocalLeaderElector()

val result = election.runIfLeader("daily-job") {
    processData()
}
// result == processData() on success, null if lock not acquired
```

### Coroutine suspend single-leader

```kotlin
val election = LocalSuspendLeaderElector()

val result = election.runIfLeader("nightly-sync") {
    syncToRemote()
}
```

### Multi-leader group (semaphore)

```kotlin
val options = LeaderGroupElectionOptions(maxLeaders = 3)
val election = LocalLeaderGroupElector(options)

// Up to 3 concurrent calls can run this action at once
val result = election.runIfLeader("parallel-batch") {
    processChunk()
}

println(election.activeCount("parallel-batch"))   // 0–3
println(election.availableSlots("parallel-batch")) // 3 - activeCount
```

### State inspection

```kotlin
val single: LeaderState = LocalLeaderElector(
    LeaderElectionOptions(nodeId = "node-a")
).state("daily-job")
println(single.status)        // Empty or Occupied
println(single.leader?.leaderId)

val group: LeaderGroupState = election.state("parallel-batch")
println(group.activeCount)    // current leader count
println(group.maxLeaders)     // maxLeaders from options
println(group.leaders.map { it.leaderId })
```

State inspection is best-effort reference data for diagnostics and metrics. It is not a lock acquisition primitive.

## Management Actions (Issue #532, unreleased)

`LeaderManagementActionRegistry` is an explicit, process-local operator surface for
releasing a registered single-leader lease. It performs an ownership pre-check, one
conditional release, and a post-check. The result is a sanitized
`LeaderManagementActionResult`; backend tokens, credentials, lock identities, and
exception text are never part of the result or observation.

Register the exact `LeaderLeaseHandle` returned by a lease-acquirer and close only the
registration token when that handle is no longer eligible for management actions:

```kotlin
val registry = LeaderManagementActionRegistry()
val handle = elector.tryAcquire("daily-job")
val registration = handle?.let(registry::register)

try {
    val result = registry.release("daily-job")
    println("${result.outcome}, mutation=${result.mutationAttempted}")
} finally {
    registration?.close() // idempotent; does not release the lease
    registry.closeAndDrain()
}
```

Registration is identity-based and bounded. Re-registering the same handle adds a
reference; another handle for the same lock returns `AMBIGUOUS`. `close()` never
performs backend I/O. `closeAndDrain()` rejects new actions and waits only for
already-admitted workers; it does not release arbitrary application leases.

The action registry is not connected to `runIfLeader`, group/semaphore election,
strategic election, `LeaderRouteLeaseRuntime`, or scheduled jobs. Register a handle
only at the application-owned lease boundary. A timeout before release reports
`ACTION_TIMED_OUT` with `mutationAttempted=false`; a timeout after release has begun
reports the same outcome with `mutationAttempted=true` and must not be automatically
retried. `RELEASE_UNCONFIRMED` and `RELEASE_FAILED` are not success signals.

The HTTP adapters in the Spring and Ktor modules share this mapping:

| Outcome | HTTP | Retry |
|---|---:|---|
| `RELEASED` | 200 | No |
| `INVALID_LOCK_NAME` | 400 | No |
| `NOT_REGISTERED` | 404 | No |
| `AMBIGUOUS`, `NOT_HELD`, `ACTION_IN_PROGRESS` | 409 | No |
| `ACTION_ADMISSION_REJECTED` | 429 | No |
| ownership/release/registry failures | 503 | No |
| `ACTION_TIMED_OUT` | 504 | No |

### Framework-neutral backend probe

`LeaderBackendDiagnosticsProbe.check(timeout, clock, probe)` is the shared synchronous boundary for built-in backend connectivity checks. It accepts only a positive, finite provider-native timeout, reads the supplied clock once before the callback, and never creates I/O, locks, clients, retries, threads, executors, or wall-clock deadlines. Ordinary callback `Exception` values become `UNKNOWN`; `CancellationException`, `InterruptedException` (with the interrupt flag restored), and fatal `Error` values retain identity and propagate. Returning `NOT_CHECKED` from the callback is invalid. Existing custom `checkConnectivity` or `diagnostics` overrides remain source-compatible and bypass this normalization by design.

The `LeaderBackendConnectivityReason` field explains the bounded cause without
storing exception text, credentials, endpoints, or lock names:

| Status | Reason | Interpretation |
|---|---|---|
| `UP` | `CONNECTED` | The client confirmed connectivity at the time of the probe. |
| `DOWN` | `DISCONNECTED` | The client confirmed that the backend is unavailable. |
| `UNKNOWN` | `CLIENT_STATE_UNCONFIRMED` | A bounded check could not confirm the client state. |
| `UNKNOWN` | `PROVIDER_UNSUPPORTED` | The provider intentionally has no active probe. |
| `UNKNOWN` | `PROVIDER_EXCEPTION` | An ordinary callback exception was normalized. |
| `NOT_CHECKED` | `NOT_CHECKED` | No probe ran; this is not proof of health or ownership. |

The default provider uses `PROVIDER_UNSUPPORTED` when it cannot offer an active
probe. Helper-backed providers use `CLIENT_STATE_UNCONFIRMED` when they read
client state without proving backend connectivity. The reason is descriptive
metadata: acquiring a lease through `runIfLeader` remains the ownership
decision, and readiness policy remains application-owned.

## Tenant Namespacing

Use `TenantLockNamespace` and `forTenant()` when the same logical job must run
independently per tenant without changing backend configuration. The wrapper
derives the backend lock name as `prefix:tenantId:lockName`; the default prefix
is `tenant`.

```kotlin
import io.bluetape4k.leader.TenantLockNamespace
import io.bluetape4k.leader.forTenant

val election = LocalLeaderElector()
val tenantElection = election.forTenant("tenant-a")

tenantElection.runIfLeader("daily-report-job") {
    generateTenantReport("tenant-a")
}
// backend lockName: tenant:tenant-a:daily-report-job

val namespace = TenantLockNamespace(tenantId = "tenant-a", prefix = "app")
val tenantGroup = LocalLeaderGroupElector().forTenant(namespace)

tenantGroup.runIfLeader("aggregation") {
    aggregateTenant("tenant-a")
}
// backend lockName: app:tenant-a:aggregation
```

`forTenant()` is available for blocking, coroutine, group, and virtual-thread
electors. The namespace separator `:` is reserved, so `TenantLockNamespace`
rejects `:` in the prefix, tenant id, and tenant-local lock name. Rename
caller-facing lock names such as `batch:daily` before wrapping an elector with
tenant scope.

The final generated backend lock name is still validated with the shared
255-character lock-name limit. The tenant-local lock-name budget is therefore
`255 - prefix.length - tenantId.length - 2` because the generated name includes
two separators.

## Lock Assert & Extend

`LockAssert` and `LockExtender` provide ShedLock-equivalent ergonomic APIs for asserting lock ownership and extending lease durations from within an active `@LeaderElection` / `@LeaderGroupElection` body.

### LockAssert

```kotlin
@LeaderElection(name = "report-job")
fun runReport() {
    LockAssert.assertLocked()           // throws if no active lock scope
    LockAssert.assertLocked("report-job") // throws if named lock not held

    if (!LockAssert.isLocked()) return  // query without throw
}

// In a suspend context — uses coroutineContext only (no ThreadLocal fallback)
@LeaderElection(name = "async-job")
suspend fun runAsync() {
    LockAssert.assertLockedSuspend()
    LockAssert.assertLockedSuspend("async-job")

    val held: Boolean = LockAssert.isLockedSuspend()
}
```

- `assertLocked()` / `assertLocked(lockName)` — throws `IllegalStateException` when called outside an active scope or inside a fail-open sentinel scope.
- `isLocked()` / `isLocked(lockName)` — returns `Boolean` without throwing.
- `assertLockedSuspend()` / `isLockedSuspend()` — suspend variants; inspect `coroutineContext[LockHandleElement]` only (no ThreadLocal fallback per R7).

### LockExtender

```kotlin
@LeaderElection(name = "long-job", leaseTime = 30.seconds)
fun runJob() {
    // ... 25 seconds of work ...
    LockExtender.extendActiveLock(60.seconds)  // renew TTL to now + 60s
    // ... 50 more seconds of work ...
}

// Detailed sealed result
when (val outcome = LockExtender.extendActiveLockDetailed(60.seconds)) {
    is ExtendOutcome.Extended    -> log.info { "expires at ${outcome.observedExpireAt}" }
    is ExtendOutcome.NotHeld     -> rollback()
    is ExtendOutcome.WrongThread -> log.warn { "Redisson thread-bound violation" }
    is ExtendOutcome.BackendError -> retry(outcome.cause)
}

// Java-friendly java.time.Duration overload
LockExtender.extendActiveLock(Duration.ofSeconds(60))

// Suspend variant
suspend fun runSuspend() {
    LockExtender.extendActiveLockSuspend(60.seconds)
}
```

- Returns `true` on success, `false` on failure (no active scope, fail-open, token mismatch, backend error).
- Updates `lastExtendDeadline` on the watchdog delegate to prevent watchdog from silently shrinking the extended lease (R2 mitigation).

### Lease-extension observer

> **Unreleased API:** This section describes the current `develop` implementation. The dependency examples in this
> README target released `0.4.0`, and the pinned `0.5.0` manual does not include this hook. Keep this integration on a
> matching develop/snapshot build until the promotion gate in the draft is complete.

`LeaderLeaseExtensionObservers` is the framework-neutral hook for observing terminal lease-extension attempts. It
receives events from both explicit `LockExtender` calls and `LeaderLeaseAutoExtender` watchdog ticks:

```kotlin
val registration = LeaderLeaseExtensionObservers.addObserver { event ->
    logger.info {
        "lease extension source=${event.source} execution=${event.execution} " +
            "outcome=${event.outcome::class.simpleName}"
    }
}

// This blocking example belongs inside a matching active @LeaderElection or @LeaderGroupElection scope; otherwise it
// returns NotHeld with context = null. The same applies inside a direct elector's active lease body. In a suspend
// scope, use extendActiveLockDetailedSuspend(60.seconds) inside the suspend function instead.
try {
    when (LockExtender.extendActiveLockDetailed(60.seconds)) {
        is ExtendOutcome.Extended -> processExtended()
        ExtendOutcome.NotHeld -> rollback()
        ExtendOutcome.WrongThread -> reportThreadBinding()
        is ExtendOutcome.BackendError -> retry()
        ExtendOutcome.Rejected -> recordRejectedExtension()
    }
} finally {
    registration.close()
}
```

`LeaderLeaseExtensionEvent.source` is `USER` for `LockExtender` and `WATCHDOG` for automatic renewal. The
`execution` value is `BLOCKING` or `SUSPEND`. The `outcome` is the existing `ExtendOutcome`: `Extended` carries the
observed expiry, `Rejected` means that a watchdog reservation failed, a user bounded operation queue was full, or a
queued user operation timed out before its command completed; that command may still run later. It is a skip signal,
not proof that no backend work will occur. `NotHeld` covers an absent or expired ownership (including fail-open),
`WrongThread` reports a thread-bound backend violation, and `BackendError` contains the backend exception.
`elapsedNanos` measures the caller-side delegate call; it is zero only when that call returned without running a
delegate, such as an outside-scope lookup or an immediate queue admission rejection.

The registry is process-local. Delivery uses bounded non-blocking in-flight admission (1024 permits globally and 256
per registration); saturation increments `LeaderLeaseExtensionObservers.droppedCount()` rather than waiting for a
permit or callback. Registration count and callback fan-out are not bounded by this registry, so keep application
registrations small and callbacks short. `droppedCount()` is separate from `ExtendOutcome.Rejected`: it counts only
observer-delivery admissions rejected by the registry.
`close()` is idempotent and removes only its registration. A callback already admitted may finish after `close()`, and
delivery order or drain completion is not guaranteed. A callback `Exception` is isolated; extension
`CancellationException` and `Error` are not converted into an `ExtendOutcome` or
published as events.
`BackendError.cause` remains the original backend `Exception`; core does not redact it, so custom observers must
sanitise the cause before logging or exporting.

The optional `LeaderLeaseExtensionContext` is supplied for matching user-owned active scopes and is absent for watchdog
events and scope-free or mismatched named calls. A fail-open `NotHeld` event still carries its lock name in `context`
with `auditLeaderId = null`.
Its `toString()` is redacted, but applications should still avoid logging raw `lockName` or `auditLeaderId`. See the
[unreleased lease-extension observation draft](../docs/manual/drafts/2026-08-27-issue-559-lease-extension-observation.en.md)
for adapter and lifecycle guidance.

The snippet closes after one explicit `USER` attempt. Keep the handle for the full single-leader action or component
lifetime with `autoExtend = true` when `WATCHDOG` ticks are needed; group election slots accept explicit
`LockExtender` calls but disable group auto-extension and therefore do not emit `WATCHDOG` events.

### ⚠️ Reactor non-suspend operator limitation (R5)

Calling `LockAssert.assertLocked()` or `LockExtender.extendActiveLock()` inside non-suspend Reactor operators (`.map {}`, `.filter {}`) will fail — neither ThreadLocal nor `CoroutineContext` is available there.

Use the suspend variants inside `mono {}` builder instead:

```kotlin
// NOT recommended — fails in async/cross-thread Reactor operators
mono.map { LockAssert.assertLocked() }

// Recommended — works correctly
mono.flatMap { value ->
    mono {
        withContext(LockHandleElement(handle)) {
            LockAssert.assertLockedSuspend()
            value
        }
    }
}
```

## Leader Identity

Every elected leader carries a string identity (`leaderId`) that is stamped on the lock record and
propagated to audit events, Redis payloads, and monitoring dashboards.

### `LeaderIdProvider`

```kotlin
fun interface LeaderIdProvider {
    fun nextLeaderId(lockName: String): String
}
```

**Contract**:
- Must never throw.
- Must never block.
- Must be thread-safe.
- Must return a non-blank string.

### Built-in providers

| Provider | Description | Default |
|----------|-------------|---------|
| `RandomLeaderIdProvider(length)` | Base58 random string (~70 bits of entropy at length 12) | `length = 12` |
| `HostnamePidLeaderIdProvider(suffixLength)` | `hostname:PID:base58suffix` — human-readable, PII-risk in multi-tenant SaaS | `suffixLength = 8` |
| `CompositeLeaderIdProvider(prefix, separator, delegate)` | Prepends a fixed prefix to another provider's output; useful for tenant tagging | |

> **PII warning**: `HostnamePidLeaderIdProvider` includes the hostname, which may identify internal
> infrastructure in multi-tenant environments. Use `RandomLeaderIdProvider` when anonymity is required.

### `LeaderIdSource` (provenance tag)

`LeaderIdSource` is a bounded enum recorded as a Micrometer tag:

| Value | Meaning |
|-------|---------|
| `LITERAL` | Static string from the `@LeaderElection(leaderId = "...")` annotation field |
| `SPEL` | Resolved from a SpEL expression in the annotation |
| `PROPERTY` | Resolved from a Spring `${...}` placeholder |
| `AUTO` | Generated by the configured `LeaderIdProvider` bean |

### `LeaderSlot` — audit identity carrier

`LeaderSlot` couples a lock name with the elected leader's identity:

```kotlin
val slot = LeaderSlot(lockName = "batch-job", leaderId = "node-42:aBcDeFgH")
val result = leaderElector.runIfLeader(slot) { doWork() }
```

The `leaderId` is:
- Stamped on the backend lock record (Redis key / DB row) for crash-recovery attribution.
- Propagated to `LeaderElectionEvent.Elected.leaderId`.
- Available via `LeaderRunResult.Elected.leaderId` when using `runIfLeaderResult`.

### Configuring a custom provider

```kotlin
// Simple random (default)
val provider = RandomLeaderIdProvider()

// Hostname + PID (human-readable, use only where hostnames are not PII)
val provider = HostnamePidLeaderIdProvider(suffixLength = 6)

// Tenant-prefixed: "tenant-acme:aBcDeFgHiJkL"
val provider = CompositeLeaderIdProvider(
    prefix = "tenant-acme",
    separator = ":",
    delegate = RandomLeaderIdProvider.Default,
)

// Couple the provider with a lock name using LeaderSlot
val slot = LeaderSlot.of("daily-job", provider)

// Then pass the slot to runIfLeader
val elector = LocalLeaderElector()
val result = elector.runIfLeader(slot) { doWork() }
```

### Audit identity in Redis group backends

When using the Lettuce or Redisson **group** backends, the `leaderId` is persisted alongside the
slot token for crash-recovery attribution:

| Backend | Storage | Key |
|---------|---------|-----|
| `leader-redis-lettuce` (group) | `lg:{lockName}:meta` Hash | `auditLeaderId` field per slot token |
| `leader-redis-redisson` (group) | `lg:{lockName}:audit` RMap | slot token → leaderId |

On crash, TTL expiry reclaims both the slot token and the identity record. No external reaper is
required.

> **Single-leader backends** (`LettuceLeaderElector`, `RedissonLeaderElector`) store the
> `auditLeaderId` in-memory on the `LeaderLockHandle`; it is not persisted to Redis.

## Dependency

```kotlin
// build.gradle.kts
implementation("io.github.bluetape4k.leader:bluetape4k-leader-core:0.4.0")
```
