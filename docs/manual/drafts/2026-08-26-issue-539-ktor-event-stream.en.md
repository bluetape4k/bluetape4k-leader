---
title: "Ktor leader event stream"
locale: en
status: unreleased
sourceReleaseRef: 0.5.0
sourceReleaseCommit: 721a9a3808f67489d2bdb8177734325981c24977
---

# Ktor leader event stream

> Unreleased draft for Issue #539 and Epic #701. The pinned `0.5.0` manual remains unchanged because this API is newer than `sourceReleaseCommit`. Promote this draft only after the release manifest points at a commit containing the event stream.

## Installation and route ownership

The event stream is disabled by default. Enable it only when the configured elector also
implements `LeaderElectionEventPublisher`, and register `leaderElectionEventStream()` inside
the caller's authenticated or otherwise authorized route. The plugin never creates an
unauthenticated root route.

```kotlin
install(SSE)
install(WebSockets) // only when WebSocket transport is enabled

install(LeaderElectionPlugin) {
    leaderElection = listeningElector // SuspendLeaderElector + LeaderElectionEventPublisher
    eventStreamRouteEnabled = true
    eventStreamSseEnabled = true
    eventStreamWebSocketEnabled = true
}

routing {
    authenticate("operations") {
        leaderElectionEventStream()
    }
}
```

`ktor-server-sse` and `ktor-server-websockets` are compile-only optional dependencies. The
application supplies and installs the matching Ktor plugins. SSE uses
`eventStreamRoutePath` (default `/management/leaderElection/events`); WebSocket uses that path
with `/ws` appended. A `lockName` query parameter is required unless
`eventStreamAllLocksEnabled = true`, which also requires `eventStreamExposeLockName = true`.

## Replay, payload, and bounds

The hub assigns a monotonic `sequence` to each `Elected`, `Revoked`, or `Skipped` event. SSE
frames use it as the event id. Clients can request replay with `afterSequence` or SSE's
`Last-Event-ID`, but not both. A future cursor is live-only; a cursor older than the bounded
ring emits a `replay_gap` control frame. `eventStreamReplayCapacity = 0` disables replay while
retaining live delivery. Malformed lock names and cursors use the stable 400 error contract.

Payloads omit lock names, leader metadata, `LeaderLease`, and backend addresses by default. Opt
into `eventStreamExposeLockName` and `eventStreamExposeLeaderMetadata` only for a trusted
consumer. Heartbeats are `{"event":"heartbeat"}`. Connection channels and replay are bounded;
slow consumers drop the oldest item, and `eventStreamMaxConnections` (1..1024) rejects excess
connections with `BACKEND_UNAVAILABLE` (503).

## Lifecycle and promotion gate

The hub is an application-owned resource. Shutdown waits for the collector and all connection
channels to finish before the plugin resource registry reports completion. It does not close the
caller-owned elector, publisher, or backend.

Before promotion, verify authenticated SSE and WebSocket test-host sessions, replay and
`replay_gap`, heartbeat, duplicate-cursor rejection, connection admission, peer disconnect
cleanup, optional-classpath isolation, and the full `:bluetape4k-leader-ktor:test` suite at the
exact release commit.
