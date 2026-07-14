---
title: "Single, group, and strategic contracts"
description: "Understand what each model guarantees—and what work partitioning remains outside the library."
releaseRef: 0.4.0
releaseCommit: 17ab7f872c1f96318c73d3580729cac20a67e017
---

# Single, group, and strategic contracts

Understand what each model guarantees—and what work partitioning remains outside the library.

## Single

One lock name permits at most one compliant owner. The action is still at-least-once from a business perspective when leases expire or callers retry, so external effects need idempotency.

## Group

A group exposes up to `maxLeaders` independent slots and reports `activeCount`, `availableSlots`, and `isFull`. Slot ownership limits concurrency but does not assign unique work. Group event projections may omit the concrete leaders because revoke events do not always identify a slot.

## Strategic

Strategic election separates candidate registration from winner selection. Built-ins include FIFO, seeded random, and scored selection with idle-time, success-rate, recent-success, and weighted scorers. It optimizes placement; it does not by itself provide a distributed lock for arbitrary shared writes.

## Release sources

- [`leader-core/README.md`](../../../../leader-core/README.md)
- [`leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderGroupState.kt`](../../../../leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderGroupState.kt)
- [`leader-core/src/main/kotlin/io/bluetape4k/leader/StrategicLeaderElector.kt`](../../../../leader-core/src/main/kotlin/io/bluetape4k/leader/StrategicLeaderElector.kt)

## Continue learning

- [Bluetape4k Leader manual](../index.md)
- [Choose an election model](../guides/election-model-selection.md)
- [Execution APIs](execution-apis.md)
