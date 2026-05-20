# Async acquisition should keep caller executors free

## Context

Issue #320 tightened `CompletableFuture` leader acquisition after the
monotonic-deadline fixes. Some async electors returned futures but still wrapped
blocking `tryLock(waitTime, leaseTime)` loops in caller executors, so retry
waits could occupy the supplied executor under contention.

## Decision

Describe this as a CPU-bounded async boundary, not IO-native non-blocking for
every backend.

- Lettuce single-leader acquisition can use native Lettuce async Redis commands.
- MongoDB `MongoCollection` uses the sync driver here, so each database attempt
  is still blocking I/O. Isolate those attempts on `VirtualThreadExecutor` and
  schedule retry waits with `CompletableFuture.delayedExecutor`.
- Do not claim Mongo sync-driver acquisition is IO non-blocking. The guarantee
  is that caller-provided action executors are not retained by acquisition retry
  sleeps.

## Outcome

`MongoLeaderElector`, `MongoLeaderGroupElector`, and `LettuceLeaderElector`
async paths no longer wrap blocking wait-loop `tryLock` calls in caller
executors. New contention tests use a single-thread caller executor and prove a
marker task can run while acquisition waits for timeout.

## Verification

- `./gradlew :bluetape4k-leader-mongodb:compileKotlin :bluetape4k-leader-mongodb:compileTestKotlin --no-build-cache --stacktrace`
- `./gradlew :bluetape4k-leader-redis-lettuce:compileKotlin :bluetape4k-leader-redis-lettuce:compileTestKotlin --no-build-cache --stacktrace`
- `./gradlew :bluetape4k-leader-mongodb:test --no-build-cache --stacktrace`
- `./gradlew :bluetape4k-leader-redis-lettuce:test --no-build-cache --stacktrace`

## Future Guard

When changing async leader-elector paths, check both the acquisition attempt and
the retry wait. Virtual threads are acceptable for isolating sync-driver
blocking I/O, but retry waits should use timer/delay mechanisms instead of
sleeping inside caller executors.
