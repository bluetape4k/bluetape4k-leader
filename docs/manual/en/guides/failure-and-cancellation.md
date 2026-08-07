---
title: "Failure and cancellation"
description: "Separate ordinary contention, action failure, backend failure, cancellation, and ownership loss."
releaseRef: 0.5.0
releaseCommit: 721a9a3808f67489d2bdb8177734325981c24977
---

# Failure and cancellation

Separate ordinary contention, action failure, backend failure, cancellation, and ownership loss.

## Five outcomes

Contention returns skipped. Action exceptions propagate from nullable APIs or appear as `ActionFailed` in result APIs. Backend acquire failures are infrastructure errors and are not disguised as contention. Cancellation propagates. Lease extension can additionally report `NotHeld`, `WrongThread`, or `BackendError`.

## Caller policy

Count skipped separately from failed. Retry a backend error only after classifying it and checking whether the action may already have started. Never retry an action failure blindly. On `NotHeld`, stop or fence remaining external writes because another contender may acquire ownership.

## Shutdown

Stop scheduling new attempts, cancel scoped coroutine jobs, wait for bounded in-flight work, then close the elector's backend client according to its ownership contract. Framework integrations help bind this sequence to application lifecycle, but the application still owns the action's cancellation safety.

## Release sources

- [`leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderRunResult.kt`](../../../../leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderRunResult.kt)
- [`leader-core/src/main/kotlin/io/bluetape4k/leader/ExtendOutcome.kt`](../../../../leader-core/src/main/kotlin/io/bluetape4k/leader/ExtendOutcome.kt)
- [`leader-core/src/test/kotlin/io/bluetape4k/leader/LeaderRunResultTest.kt`](../../../../leader-core/src/test/kotlin/io/bluetape4k/leader/LeaderRunResultTest.kt)

## Continue learning

- [Bluetape4k Leader manual](../index.md)
- [Lease lifecycle](lease-lifecycle.md)
- [Observability and operations](observability-and-operations.md)
