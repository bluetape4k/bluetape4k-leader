---
title: "Ktor plugin lifecycle and graceful shutdown"
locale: en
status: unreleased
sourceReleaseRef: 0.5.0
sourceReleaseCommit: 721a9a3808f67489d2bdb8177734325981c24977
---

# Ktor plugin lifecycle and graceful shutdown

> Unreleased draft for Issue #541 and Epic #701. The pinned `0.5.0` manual stays limited to APIs present at `sourceReleaseCommit`.

Installing `LeaderElectionPlugin` creates an application-owned resource boundary. Each
`leaderScheduled` Job is registered in that boundary. On `ApplicationStopped`, the registry
marks itself closed, cancels registered Jobs immediately, and performs the bounded join on its
own cleanup dispatcher without blocking the Ktor stop callback. Closing is idempotent and does
not close the supplied `SuspendLeaderElector`, Redis/SQL/Mongo client, or another backend that
the application owns.

When `leaderScheduled` is called with an explicit elector and the plugin is not installed, the
Job uses the normal Application scope and cancellation remains the caller's responsibility.

Promotion requires deterministic `ApplicationStopped` cancellation tests during acquire and
action, plus the normal contention-null contract.
