---
title: "Execution APIs"
description: "Map each interface to its lambda, return type, scheduler, and cancellation surface."
releaseRef: 0.5.0
releaseCommit: 721a9a3808f67489d2bdb8177734325981c24977
---

# Execution APIs

Map each interface to its lambda, return type, scheduler, and cancellation surface.

## Interface map

`LeaderElector` accepts `() -> T` and returns `T?`. `AsyncLeaderElector` accepts `() -> CompletableFuture<T>` and returns `CompletableFuture<T?>`. `VirtualThreadLeaderElector` accepts `() -> T` and returns `VirtualFuture<T?>`. `SuspendLeaderElector` accepts a suspend lambda and returns `T?`. Group interfaces mirror the blocking and suspend shapes.

## Do not adapt by convenience

Choose the interface at the application boundary. Wrapping a blocking backend in a coroutine does not make its I/O non-blocking, and nesting arbitrary thread hops can break thread-bound ownership such as Redisson extension. Prefer the backend's native interface.

## Slot overloads

`LeaderSlot` carries a lock name and audit leader id. Default bridge overloads warn and cannot prove that a backend stamped the supplied identity; backend implementations should override slot and result variants. Do not assume `Elected.leaderId` is populated when a bridge path returns null.

## Release sources

- [`leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderElector.kt`](../../../../leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderElector.kt)
- [`leader-core/src/main/kotlin/io/bluetape4k/leader/AsyncLeaderElector.kt`](../../../../leader-core/src/main/kotlin/io/bluetape4k/leader/AsyncLeaderElector.kt)
- [`leader-core/src/main/kotlin/io/bluetape4k/leader/VirtualThreadLeaderElector.kt`](../../../../leader-core/src/main/kotlin/io/bluetape4k/leader/VirtualThreadLeaderElector.kt)
- [`leader-core/src/main/kotlin/io/bluetape4k/leader/coroutines/SuspendLeaderElector.kt`](../../../../leader-core/src/main/kotlin/io/bluetape4k/leader/coroutines/SuspendLeaderElector.kt)

## Continue learning

- [Bluetape4k Leader manual](../index.md)
- [Result semantics](result-semantics.md)
- [Choose an execution API](../guides/execution-model-selection.md)
