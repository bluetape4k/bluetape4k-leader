# bluetape4k-leader-ktor

English | [한국어](./README.ko.md)

Ktor 3.x integration module for `bluetape4k-leader`. Provides a Ktor application plugin
DSL and a Spring-`@Scheduled`-style helper that runs leader-only tasks on a fixed period
within the application coroutine scope.

## Architecture

`leader-ktor` adds three pieces of glue on top of `leader-core`:

1. **`LeaderElectionPlugin`** — a `createApplicationPlugin` DSL that captures a
   `SuspendLeaderElector` (and optionally a `SuspendLeaderGroupElector`) and stores it
   in the `Application.attributes` map so it can be reused by extension functions.
2. **`leaderElectionPluginConfig()`** — extension on `Application` to retrieve the
   stored configuration.
3. **`Application.leaderScheduled(...)`** — schedules a leader-only `suspend` action on
   a fixed period. When `LeaderElectionPlugin` is installed, the returned Job is registered
   as an application-owned resource and cancelled with a bounded join at `ApplicationStopped`.

![leader ktor Architecture diagram](../docs/images/readme-diagrams/leader-ktor-architecture-01.png)

### Runtime sequence

![leader ktor Sequence Flow diagram](../docs/images/readme-diagrams/leader-ktor-sequence-01.png)

## Core Features

- Ktor 3.x compatible, coroutine-native (`SuspendLeaderElector` based)
- Application-owned scheduler Job cancellation at `ApplicationStopped` through the plugin
  resource registry; caller-owned electors and backend clients are never closed implicitly
- Per-cycle exception isolation — `action` exceptions are logged and the next cycle
  continues (poison-pill prevention)
- `CancellationException` is always re-thrown so structured concurrency works
- Validation: `lockName` must be non-blank; `period` must be positive
- Pluggable backend: any `SuspendLeaderElector` implementation
  (`leader-redis-redisson`, `leader-redis-lettuce`, `leader-mongodb`, etc.)

## Usage Examples

```kotlin
import io.bluetape4k.leader.ktor.LeaderElectionPlugin
import io.bluetape4k.leader.ktor.leaderScheduled
import io.bluetape4k.leader.redisson.RedissonSuspendLeaderElector
import io.ktor.server.application.Application
import io.ktor.server.application.install
import kotlin.time.Duration.Companion.minutes

fun Application.module() {
    val redisson = redissonClient()

    install(LeaderElectionPlugin) {
        leaderElection = RedissonSuspendLeaderElector(redisson)
    }

    leaderScheduled("daily-report", period = 1.minutes) {
        reportService.generate()
    }
}
```

Manual cancellation:

```kotlin
val job = leaderScheduled("inventory-sync", 5.minutes) { syncInventory() }
// ... later
job.cancel()
```

### Lifecycle ownership

`LeaderElectionPlugin` creates one application-owned resource registry. Jobs returned by
`leaderScheduled` are registered there and are cancelled immediately when
`ApplicationStopped` is observed; cleanup then performs a bounded join without blocking the
Ktor stop callback. Resource cleanup is idempotent and runs outside the registry lock.

The plugin does not close the supplied `SuspendLeaderElector`, Redis/SQL/Mongo client, or
any other backend owned by the application. If `leaderScheduled` receives an explicit elector
without the plugin, it remains in the normal `Application` scope and the caller owns its
cancellation. Normal lock contention still returns `null` and the scheduler continues with
the next cycle.

Bypassing the plugin (advanced — pass the elector explicitly):

```kotlin
leaderScheduled(
    lockName = "ad-hoc",
    period = 30.seconds,
    leaderElection = customElector,
) {
    doWork()
}
```

## Configuration Options

| Field                 | Type                          | Required | Description                              |
|-----------------------|-------------------------------|----------|------------------------------------------|
| `leaderElection`      | `SuspendLeaderElector?`       | Yes      | Single-leader elector backend            |
| `leaderGroupElection` | `SuspendLeaderGroupElector?`  | No       | Group/multi-leader elector (optional)    |
| `managementRouteEnabled` | `Boolean`                  | No       | Enables `GET /management/leaderElection` |
| `managementRoutePath` | `String`                      | No       | Management route path                    |
| `backendDiagnosticsRouteEnabled` | `Boolean`           | No       | Enables `GET /management/leaderElection/diagnostics` |
| `backendDiagnosticsRoutePath` | `String`                | No       | Backend diagnostics route path           |
| `backendConnectivityCheckEnabled` | `Boolean`          | No       | Runs one active connectivity probe per request |
| `backendConnectivityCheckTimeout` | `kotlin.time.Duration` | No    | Positive, finite probe timeout; defaults to `500ms` |
| `managementActionRouteEnabled` | `Boolean` | No | Validates an application-owned action registry; route install remains explicit |
| `managementActionRegistry` | `SuspendLeaderManagementActionRegistry?` | No | Application-owned single-leader action registry |
| `managementActionRoutePath` | `String?` | No | Explicit action path override; defaults to `<managementRoutePath>/actions` when passed to the route |

`leaderScheduled` parameters:

| Parameter        | Type                       | Default                              | Notes                                       |
|------------------|----------------------------|--------------------------------------|---------------------------------------------|
| `lockName`       | `String`                   | —                                    | Must be non-blank                           |
| `period`         | `kotlin.time.Duration`     | —                                    | Must be positive                            |
| `leaderElection` | `SuspendLeaderElector`     | from installed plugin                | Falls back to plugin config if omitted      |
| `action`         | `suspend () -> Unit`       | —                                    | Executed only when this node is leader      |

## Management Route

The management route is disabled by default. Enable it explicitly and register static lock names when you want them visible before the first scheduled run:

```kotlin
fun Application.module() {
    install(LeaderElectionPlugin) {
        leaderElection = redissonElector
        managementRouteEnabled = true
        managementLockNames("batch-job", "migration-gate")
    }
}
```

```http
GET /management/leaderElection
```

The route is installed on the main Ktor application port and routing pipeline. Protect it with an authentication plugin, network policy, or a dedicated internal port before exposing it outside a trusted management boundary.

```json
{
  "locks": [
    {
      "name": "batch-job",
      "status": "Empty",
      "leaderId": null,
      "leaseExpiry": null
    }
  ]
}
```

`leaderScheduled()` records its lock name into the management registry when the plugin is installed. The route emits JSON text directly, so applications do not need to install Ktor content negotiation just for this endpoint.

### Stable error responses

Management and adapter failures use a small, stable JSON contract. Normal lock
contention remains `null`/skip and is not converted to an HTTP error.

| Code | HTTP status | Meaning |
|---|---:|---|
| `INVALID_LOCK_NAME` | 400 | The lock name is blank or outside the core ASCII grammar |
| `NOT_LEADER` | 503 | The current leader state does not allow the request |
| `LEADER_LOCKED` | 423 | The leader lock is already held |
| `BACKEND_UNAVAILABLE` | 503 | State/backend access failed |
| `CONFIGURATION`, `INTERNAL` | 500 | Configuration or unexpected request failure |
| `INVALID_CURSOR` | 400 | A stream cursor is malformed |

The response contains only `code`, `message`, and numeric `status` by default:

```json
{"code":"BACKEND_UNAVAILABLE","message":"leader backend is temporarily unavailable","status":503}
```

Backend exception messages, stack traces, and cause details are never copied to
the response. `lockName` is omitted unless a typed `LeaderElectionErrorOverride`
explicitly sets `exposeLockName = true`; status overrides are restricted to the
same allow-list above. `CancellationException` is rethrown so request
cancellation is not misclassified as an infrastructure failure.

The management route has a converter-free `respondText` fallback. Applications
that already use Ktor `StatusPages` may opt in to the adapter (the dependency is
`compileOnly` in this module):

```kotlin
import io.bluetape4k.leader.ktor.statuspages.leaderElectionErrors
import io.ktor.server.plugins.statuspages.StatusPages

install(StatusPages) {
    leaderElectionErrors()
}
```

Detached `leaderScheduled` exceptions stay outside this HTTP mapping: the
plugin logs the sanitized exception type at `WARN`, skips that iteration, and
continues with the next schedule.

## Management Action Route (Issue #532, unreleased)

The write route is a separate, explicit opt-in. `LeaderElectionPlugin` validates that
an application-owned `SuspendLeaderManagementActionRegistry` is present when
`managementActionRouteEnabled=true`, but it never installs a POST route. Install the
route inside the application's own `authenticate("management")` scope:

```kotlin
val actionRegistry = SuspendLeaderManagementActionRegistry()

install(Authentication) {
    basic("management") {
        validate { credentials ->
            if (credentials.name == "admin" && credentials.password == "secret") {
                UserIdPrincipal(credentials.name)
            } else {
                null
            }
        }
    }
}

install(LeaderElectionPlugin) {
    leaderElection = redissonElector
    managementActionRouteEnabled = true
    managementActionRegistry = actionRegistry
}

routing {
    authenticate("management") {
        leaderElectionManagementActionRoute(
            registry = actionRegistry,
            authorize = { principal<UserIdPrincipal>() != null },
        )
    }
}
```

The canonical path is `POST /management/leaderElection/actions/{lockName}`. Pass an
explicit `path` (for example `/internal/leader-status/actions`) when
`managementRoutePath` is customized; the action path is not inferred by an automatic
route installation. The route uses the shared ASCII lock-name grammar. An encoded or
literal slash stays outside the selector boundary and returns 404; a matched hostile
selector such as `%` returns 400 with `INVALID_LOCK_NAME`.

Ktor authentication owns unauthenticated 401 and principal failures. A false
`authorize` callback returns 403 `AUTHORIZATION_DENIED`; an ordinary callback exception
returns 500 `AUTHORIZATION_FAILED` without invoking the registry or copying exception
text. Successful and typed registry outcomes use the common HTTP mapping and an
allow-listed JSON body containing only `action`, `outcome`, and `mutationAttempted`.
There is no automatic retry, including for `ACTION_TIMED_OUT` or
`RELEASE_UNCONFIRMED`.

The application owns the registry, observer, and scope. Drain it before stopping the
engine; the helper keeps shutdown suspend-native and never cancels an external
application scope:

```kotlin
suspend fun shutdown(
    engine: ApplicationEngine,
    actionRegistry: SuspendLeaderManagementActionRegistry,
) {
    engine.stopLeaderManagementGracefully(
        actionRegistry,
        gracePeriodMillis = 1_000,
        timeoutMillis = 5_000,
    )
}
```

`closeAndDrain()` is bounded. If it returns `false`, the helper logs a sanitized
warning and still stops the engine; it does not release arbitrary leases. Register
only single-leader lease handles explicitly. `runIfLeader`, group/strategic election,
`leaderScheduled`, and `LeaderRouteLeaseRuntime` are not auto-registered.

## Backend Diagnostics Route

The backend diagnostics route is disabled by default. Enable it to expose the selected backend descriptor:

```kotlin
install(LeaderElectionPlugin) {
    leaderElection = redissonElector
    backendDiagnosticsRouteEnabled = true
}
```

```http
GET /management/leaderElection/diagnostics
```

The default response performs no backend I/O and reports connectivity as `NOT_CHECKED`. Enable the active check only when each request may probe the backend:

```kotlin
install(LeaderElectionPlugin) {
    leaderElection = redissonElector
    backendDiagnosticsRouteEnabled = true
    backendConnectivityCheckEnabled = true
    backendConnectivityCheckTimeout = 500.milliseconds
}
```

The route calls `LeaderBackendDiagnosticsProvider.checkConnectivity()` once on `Dispatchers.IO` with the configured timeout. Unsupported or indeterminate checks return `UNKNOWN`. Electors may expose the provider directly or through `LeaderBackendDiagnosticsAware`; plugin installation fails with a clear error when diagnostics are enabled but no provider is available.

Protect the route before exposing it outside a trusted management boundary. Connectivity diagnostics are not proof that this process currently owns a leader lease.

## LockAssert / LockExtender inside `leaderScheduled` (Issue #79)

`LockAssert.assertLockedSuspend()` and `LockExtender.extendActiveLockDetailedSuspend(d)`
work inside the `leaderScheduled { ... }` background action — the underlying
`SuspendLeaderElector`'s capture mechanism propagates `LockHandleElement` through
the action's `CoroutineContext`.

```kotlin
leaderScheduled("daily-report", period = 1.hours) {
    LockAssert.assertLockedSuspend()                              // passes when we are leader
    val outcome = LockExtender.extendActiveLockDetailedSuspend(10.minutes)
    if (outcome is ExtendOutcome.Extended) {
        runLongRunningReport()
    }
}
```

**Unsupported scenarios**: `Application.routing` handlers, `PipelineContext`,
or any non-`leaderScheduled` surface. The plugin only stores configuration in
`Application.attributes`; `LockHandleElement` is not injected into Ktor's
routing pipeline. Use `leaderScheduled` for guaranteed propagation.

## Dependency

Gradle (Kotlin DSL):

```kotlin
dependencies {
    implementation("io.github.bluetape4k.leader:bluetape4k-leader-ktor:$bluetape4kLeaderVersion")
    implementation("io.github.bluetape4k.leader:bluetape4k-leader-redis-redisson:$bluetape4kLeaderVersion") // or another backend
    implementation("io.ktor:ktor-server-core:3.4.3")

    testImplementation("io.github.bluetape4k:bluetape4k-ktor-testing")
}
```

The `ktor-server-core` artifact is `compileOnly` in this module — your application
must declare it explicitly.

Use `bluetape4k-ktor-core` at the application layer when the same Ktor service
also needs shared JSON, error response, health, or readiness helpers. This module
does not take a runtime dependency on `bluetape4k-ktor-core` because its public
surface is the leader-election plugin and scheduler DSL, and the management route
emits JSON text without requiring a content-negotiation plugin.

## License

MIT License
