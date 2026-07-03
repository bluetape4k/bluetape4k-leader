# Suspend cancellation review

Date: 2026-07-04

Scope:
- Issues: #568, #569
- Modules: `leader-core`, `leader-exposed-r2dbc`, `leader-redis-lettuce`, `leader-redis-redisson`
- Packages:
  - `io.bluetape4k.leader.local`
  - `io.bluetape4k.leader.exposed.r2dbc.lock`
  - `io.bluetape4k.leader.lettuce`
  - `io.bluetape4k.leader.redisson`

## Findings

1. `ExposedR2dbcLock.isHeldByCurrentInstance()` and `unlock()` wrapped `suspendTransaction` with `runCatching`.
   This converted coroutine cancellation into a logged fallback result.
2. `ExposedR2dbcGroupLock.isHeldByCurrentInstance()` and `unlock()` had the same suspend `runCatching` pattern.
3. Suspend strategic electors logged `updateResult` failures through `runCatching`, which could also swallow cancellation while recording success or failure.
4. Synchronous strategic electors still use `runCatching`, but they do not call suspend APIs and are outside the coroutine-cancellation scope of this review.

## Changes

- Replaced suspend `runCatching` blocks with explicit `try/catch`.
- Re-throw `CancellationException` before non-cancellation error handling.
- Catch `Exception` for backend/logged fallbacks instead of broad `Throwable`.
- Extracted package-internal cancellation-preserving helpers so regression tests can force cancellation and non-cancellation failures directly.
- Kept previous non-cancellation fallback behavior:
  - R2DBC `isHeldByCurrentInstance()` returns `false` on DB errors.
  - R2DBC `unlock()` logs backend errors without deleting another owner's lock.
  - Strategic elector result-update errors are logged without hiding the action result unless cancellation occurs.

## Pattern Review

- `bluetape4k-code-patterns`: PASS
  - No `runCatching` around suspend calls remains in the affected suspend paths.
  - `CancellationException` is rethrown before generic exception handling.
  - No production `runBlocking` added.
  - No `!!` added.
  - No public API shape changed.

## Verification

- `./gradlew :bluetape4k-leader-core:compileKotlin :bluetape4k-leader-core:compileTestKotlin :bluetape4k-leader-exposed-r2dbc:compileKotlin :bluetape4k-leader-exposed-r2dbc:compileTestKotlin :bluetape4k-leader-redis-lettuce:compileKotlin :bluetape4k-leader-redis-lettuce:compileTestKotlin :bluetape4k-leader-redis-redisson:compileKotlin :bluetape4k-leader-redis-redisson:compileTestKotlin --warning-mode all`
  - PASS, `BUILD SUCCESSFUL in 13s`.
- `./gradlew :bluetape4k-leader-core:test --tests 'io.bluetape4k.leader.local.LocalStrategicSuspendLeaderElectorTest' --warning-mode all`
  - PASS, 14 tests.
- `./gradlew :bluetape4k-leader-exposed-r2dbc:test --tests 'io.bluetape4k.leader.exposed.r2dbc.lock.R2dbcLockCancellationTest' --tests 'io.bluetape4k.leader.exposed.r2dbc.lock.ExposedR2dbcLockTest' --tests 'io.bluetape4k.leader.exposed.r2dbc.lock.ExposedR2dbcGroupLockTest' --warning-mode all --rerun-tasks`
  - PASS, 74 tests across H2, PostgreSQL, and MySQL, including `R2dbcLockCancellationTest`.
- `./gradlew :bluetape4k-leader-redis-lettuce:test --tests 'io.bluetape4k.leader.lettuce.LettuceStrategicSuspendLeaderElectorTest' --warning-mode all`
  - PASS, 17 tests.
- `./gradlew :bluetape4k-leader-redis-redisson:test --tests 'io.bluetape4k.leader.redisson.RedissonStrategicSuspendLeaderElectorTest' --warning-mode all`
  - PASS, 11 tests.
- Static scan:
  - `runCatching` remains only in synchronous strategic elector implementations for this search pattern.
