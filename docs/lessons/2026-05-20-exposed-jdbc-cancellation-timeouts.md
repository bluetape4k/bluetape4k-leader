# Exposed JDBC cancellation and timeout budgets

## Context

Issues #304, #305, and #306 shared the same Exposed JDBC lock/elector surface:
blocking transaction cleanup paths swallowed `CancellationException` through
`runCatching`, and lock acquisition retry loops used wall-clock time for local
timeouts.

## Decision

Use explicit `try/catch` around transaction-backed best-effort paths so
`CancellationException` is always rethrown before generic fallback logging.
Keep database lease timestamps on wall-clock `Instant`, but measure local
`tryLock` retry budgets with a small `MonotonicDeadline` helper based on
`System.nanoTime`.

## Outcome

Single-lock and group-lock retry loops now resist wall-clock jumps, while DB
cleanup/status failures keep the previous best-effort behavior for non-
cancellation exceptions.

## Verification

- `./gradlew :bluetape4k-leader-exposed-jdbc:test --tests 'io.bluetape4k.leader.exposed.jdbc.lock.MonotonicDeadlineTest' --no-build-cache --stacktrace`
  - 4 passing
- `./gradlew :bluetape4k-leader-exposed-jdbc:test --no-build-cache --stacktrace`
  - 231 passing

## Future Guidance

Do not use `runCatching` around suspend-aware or cancellation-sensitive Kotlin
paths. For distributed locks, keep persisted lease expiry comparable across JVMs
with wall-clock timestamps, but keep local wait budgets monotonic.
