---
title: "Single, group, and strategic contracts"
description: "Understand what each model guarantees—and what work partitioning remains outside the library."
releaseRef: 0.5.0
releaseCommit: 721a9a3808f67489d2bdb8177734325981c24977
---

# Single, group, and strategic contracts

Understand what each model guarantees—and what work partitioning remains outside the library.

## Interactive visual companions

Start with the detailed `LeaderElector` lock-and-lease walkthrough, then use the `LeaderGroupElector` companion to focus on the `1 → N` slot-capacity delta. Both simulations use the release-pinned Redis/Lettuce model.

[![LeaderElector lock and lease visual companion](../../assets/visual-companions/leader-elector.en.png)](/visual-companions/bluetape4k-leader/leader-elector/)

[![LeaderGroupElector slot capacity visual companion](../../assets/visual-companions/leader-group-elector.en.png)](/visual-companions/bluetape4k-leader/leader-group-elector/)

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
