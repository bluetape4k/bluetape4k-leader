---
title: "Observability and operations"
description: "Observe decisions, duration, ownership loss, and backend health without making lock names unbounded labels."
releaseRef: 0.5.0
releaseCommit: 721a9a3808f67489d2bdb8177734325981c24977
---

# Observability and operations

Observe decisions, duration, ownership loss, and backend health without making lock names unbounded labels.

## Signals

Track elected, skipped, action failure, execution duration, active ownership, and lease-extension outcomes. A skipped increase can be healthy contention or a stalled owner; interpret it with duration, state, and backend latency. Alert on sustained failures and non-transient extension errors rather than every skip.

## Cardinality

Lock names often contain tenant or job identifiers. Do not put raw unbounded names into metric tags. Normalize names in the application, pre-register static names, heed the recorder's warning for newly discovered names, and deregister retired dynamic names. Aggregate by stable job family and keep exact identifiers in structured logs or traces where retention is controlled.

## Runbook

For a suspected stuck job: confirm last elected/completed events, inspect effective history status, check backend connectivity and lease expiry, then determine whether the prior action can still write. Only after that should operators force cleanup or rerun. Record who made the decision and which fencing evidence was checked.

## Release sources

- [`leader-micrometer/README.md`](../../../../leader-micrometer/README.md)
- [`leader-core/src/main/kotlin/io/bluetape4k/leader/history/LeaderHistoryStatusExtensions.kt`](../../../../leader-core/src/main/kotlin/io/bluetape4k/leader/history/LeaderHistoryStatusExtensions.kt)
- [`examples/prometheus-dashboard/README.md`](../../../../examples/prometheus-dashboard/README.md)

## Continue learning

- [Bluetape4k Leader manual](../index.md)
- [Micrometer integration](../frameworks/micrometer.md)
- [Identity, state, and history](identity-state-and-history.md)
- [Testing leader election](testing.md)
