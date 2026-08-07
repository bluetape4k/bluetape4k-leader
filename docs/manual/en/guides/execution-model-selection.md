---
title: "Choose an execution API"
description: "Keep the elector API aligned with the application's concurrency model and cancellation rules."
releaseRef: 0.5.0
releaseCommit: 721a9a3808f67489d2bdb8177734325981c24977
---

# Choose an execution API

Keep the elector API aligned with the application's concurrency model and cancellation rules.

## Blocking and CompletableFuture

`LeaderElector` runs a synchronous lambda on the caller path. `AsyncLeaderElector` accepts a `CompletableFuture` action and defaults to the virtual-thread executor. Use blocking APIs for bounded imperative jobs; use the async API when the surrounding contract already exposes futures.

## Virtual threads

`VirtualThreadLeaderElector` accepts a direct lambda and returns `VirtualFuture`. It is useful for many blocking election attempts on Java 21+, but it does not turn blocking backend calls into non-blocking I/O. Size backend connections independently.

## Coroutines

`SuspendLeaderElector` is the natural choice in coroutine services. Use native suspend backends such as Exposed R2DBC when end-to-end non-blocking behavior matters. Do not bridge a suspend-only delegate through `runBlocking`; cancellation must release the scoped lease and propagate.

## Result and cancellation

All models offer explicit result variants. Cancellation is not converted to `ActionFailed`: blocking and suspend paths rethrow it, while future-based paths complete exceptionally. Test the observation point your caller actually uses, such as `await()` or `join()`.

## Release sources

- [`leader-core/src/main/kotlin/io/bluetape4k/leader/AsyncLeaderElector.kt`](../../../../leader-core/src/main/kotlin/io/bluetape4k/leader/AsyncLeaderElector.kt)
- [`leader-core/src/main/kotlin/io/bluetape4k/leader/VirtualThreadLeaderElector.kt`](../../../../leader-core/src/main/kotlin/io/bluetape4k/leader/VirtualThreadLeaderElector.kt)
- [`leader-core/src/main/kotlin/io/bluetape4k/leader/coroutines/SuspendLeaderElector.kt`](../../../../leader-core/src/main/kotlin/io/bluetape4k/leader/coroutines/SuspendLeaderElector.kt)

## Continue learning

- [Bluetape4k Leader manual](../index.md)
- [Execution APIs](../core/execution-apis.md)
- [Failure and cancellation](failure-and-cancellation.md)
