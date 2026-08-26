---
title: "Ktor route-scoped leader guard"
locale: en
status: unreleased
sourceReleaseRef: 0.5.0
sourceReleaseCommit: 721a9a3808f67489d2bdb8177734325981c24977
---

# Ktor route-scoped leader guard

> Unreleased draft for Issue #542 and Epic #701. Keep the release-pinned manual limited to APIs present at `sourceReleaseCommit`.

`Route.leaderGuard` (with the `leaderOnlyRoute` shorthand) applies the stable
leader-election error contract to a route. It runs after Ktor's public
`AuthenticationChecked` hook, so an enclosing `authenticate(...)` route and application-owned
authorization or rate-limit plugins run first. Unauthorized and forbidden requests do not
reach the leader backend. The module keeps `ktor-server-auth` as `compileOnly`; an application
that uses `authenticate` supplies the matching Ktor authentication artifact.

```kotlin
routing {
    authenticate("service") {
        leaderGuard("projection-refresh") {
            get { call.respondText("ok") }
        }
    }
}
```

`STATE` is the default authority mode. It reads the current `LeaderState` exactly once per
request and returns `NOT_LEADER` (503) for an empty state. This is a passive snapshot: it does
not reserve the request, extend a lease, or make downstream work atomic. The default elector
must advertise `supportsAuditLeaderState`; use an explicit `stateProvider` when the snapshot
comes from another source. Use `@LeaderElection` for atomic method execution.

Choose `authorityMode = LeaderRouteAuthorityMode.LEASE` only when the request must hold a
lease. This mode is explicit and never downgrades to `STATE`. It requires an explicit
`SuspendLeaderLeaseAcquirer` or an elector exposing that capability, and `leaseMaxDuration`
must be finite and positive. Acquire and release are bounded by that duration. Contention
returns `LEADER_LOCKED` (423), successful requests release exactly once, and a release failure
or timeout is logged without replacing the downstream response or cancellation.

Guard errors hide `lockName` and other leader metadata by default. Set `exposeMetadata = true`
only at a deliberate trusted boundary; custom status and metadata behavior remains constrained
by the typed, allow-listed `LeaderElectionErrorResponder` contract.

Promotion requires authentication-order, STATE, LEASE, release, metadata, and cancellation
tests against the intended Ktor route pipeline.
