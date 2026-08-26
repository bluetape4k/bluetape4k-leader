---
title: "Ktor integration"
description: "Install an application-scoped suspend elector and bind periodic attempts to Ktor shutdown."
releaseRef: 0.5.0
releaseCommit: 721a9a3808f67489d2bdb8177734325981c24977
---

# Ktor integration

Install an application-scoped suspend elector and bind periodic attempts to Ktor shutdown.

## Plugin

`LeaderElectionPlugin` exposes a configured `SuspendLeaderElector` through the Ktor application. The application decides which backend client and elector it owns. Keep one lifecycle owner so plugin shutdown does not race a separately closed client.

## Scheduling

`Application.leaderScheduled(lockName, period) { ... }` launches periodic election attempts. Only the elected instance runs the body, and the job is cancelled on `ApplicationStopped`. Set lease longer than one normal iteration or use a supported extension strategy for variable work.

## Event stream

The Issue #701 event stream is disabled by default. When the elector implements
`LeaderElectionEventPublisher`, enable it and register `leaderElectionEventStream()` inside
your `authenticate { ... }` (or equivalent authorization) route. Install `SSE` and/or
`WebSockets` yourself; both transport artifacts are optional compile-only dependencies. The
plugin does not create a public root route.

The configured path defaults to `/management/leaderElection/events`; WebSocket clients use
`${path}/ws`. Each connection filters by `lockName` unless all-lock mode is explicitly enabled.
Events are assigned monotonic sequence ids and can be replayed with `afterSequence` or SSE's
`Last-Event-ID`. Replay is bounded, stale cursors produce `replay_gap`, and capacity `0` is
live-only. Payload metadata is redacted by default, heartbeats keep idle connections alive, and
the connection limit plus drop-oldest channels bound resource use. Invalid input and admission
failures use the stable Ktor error contract.

## What remains yours

The helper does not persist missed schedules, serialize retries, or make side effects idempotent. Record durable work state if a run must be recovered. Test two Ktor applications against one backend and verify both exclusive execution and shutdown cancellation.

## Release sources

- [`leader-ktor/src/main/kotlin/io/bluetape4k/leader/ktor/LeaderElectionPlugin.kt`](../../../../leader-ktor/src/main/kotlin/io/bluetape4k/leader/ktor/LeaderElectionPlugin.kt)
- [`leader-ktor/README.md`](../../../../leader-ktor/README.md)
- [`examples/ktor-app/src/test/kotlin/io/bluetape4k/leader/examples/ktor/KtorAppTest.kt`](../../../../examples/ktor-app/src/test/kotlin/io/bluetape4k/leader/examples/ktor/KtorAppTest.kt)

## Continue learning

- [Bluetape4k Leader manual](../index.md)
- [Spring Boot or Ktor](../guides/spring-vs-ktor.md)
- [Migrate a scheduled job](../guides/scheduled-job-migration.md)
