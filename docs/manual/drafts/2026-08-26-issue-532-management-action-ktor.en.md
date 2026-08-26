---
title: "Ktor management action route"
locale: en
status: unreleased
sourceReleaseRef: 0.5.0
sourceReleaseCommit: 721a9a3808f67489d2bdb8177734325981c24977
---

# Ktor management action route

> Unreleased draft for Issue #532. The pinned `0.5.0` manual remains unchanged because this route is newer than `sourceReleaseCommit`. Promote this draft only after a release manifest points at a commit that contains the route.

## Route ownership

Ktor exposes a `Route.leaderElectionManagementActionRoute` extension for a POST
release action. The plugin does not install it. The application must install the
extension inside its own `authenticate("management")` block and must provide the
application-owned `SuspendLeaderManagementActionRegistry`:

```kotlin
val actionRegistry = SuspendLeaderManagementActionRegistry()

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

`managementActionRouteEnabled` defaults to `false`. Enabling it without a registry
fails during plugin installation. Installing the extension without enabling it is
fail-closed and returns 404. The plugin does not add authentication, authorization,
or an external coroutine scope.

## Canonical and custom paths

The canonical path is:

```http
POST /management/leaderElection/actions/{lockName}
```

When `managementRoutePath` is customized, pass the matching action path explicitly,
for example `/internal/leader-status/actions`. Keep the read-only GET route and the
write route separate. Normalize a path once at installation; an empty path is invalid.

The selector uses the shared ASCII grammar
`[A-Za-z0-9][A-Za-z0-9._:-]{0,127}` and is checked before registry mutation. A literal
or encoded slash does not cross the selector boundary and returns 404. A matched
hostile selector such as `%`, `*`, `..`, control text, or a non-ASCII value returns
400 `INVALID_LOCK_NAME` and never calls the registry.

## Authentication and response boundary

Ktor's provider owns unauthenticated 401 and principal failures. The application
`authorize` callback owns the next decision:

- `false` returns 403 `AUTHORIZATION_DENIED`.
- An ordinary callback exception returns 500 `AUTHORIZATION_FAILED`.
- Cancellation is rethrown; fatal `Error` values are not converted to a response.

The registry is not invoked for either authorization failure, and the response does
not copy credentials, actor data, lock names, backend payloads, tokens, or exception
text. Action responses use the framework-neutral core mapping and contain only:

```json
{"action":"RELEASE","outcome":"RELEASED","mutationAttempted":true}
```

All outcomes have `retryAllowed=false`. In particular, do not retry a timeout before
the worker has terminalized, and do not treat `RELEASE_UNCONFIRMED` or
`RELEASE_FAILED` as success.

## Graceful shutdown

The application owns the shutdown order. Call the `ApplicationEngine` helper before
the engine is otherwise stopped:

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

The helper calls `closeAndDrain()` first and then `stopSuspend`. A drain timeout logs a
sanitized warning, returns `false`, and still stops the engine. It does not cancel the
application scope or release an arbitrary lease. The registry's own worker scope and
registration lifecycle remain bounded and application-owned.

## Promotion gate

Before promotion, verify the explicit-install 404, auth 401/403/500, selector 404/400,
allow-listed JSON, cancellation propagation, shutdown ordering, and full Ktor tests at
the exact release commit. Keep this draft out of the pinned manual until those checks
and the release manifest update are complete.
