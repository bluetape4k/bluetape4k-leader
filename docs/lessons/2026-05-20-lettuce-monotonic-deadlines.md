# Lettuce monotonic acquisition deadlines

## Context

Issue #309 found wall-clock `System.currentTimeMillis()` wait budgets in
Lettuce lock and slot acquisition paths. The backend already uses Redis server
time for lease expiry, so the unsafe clock was limited to client-side retry
deadlines.

## Decision

Add a Lettuce-local `MonotonicDeadline` helper based on `System.nanoTime`.
Use it for blocking, async, and suspend lock acquisition and slot-token
acquisition. Clamp fixed retry delays to the remaining monotonic budget so the
last sleep does not intentionally exceed the requested wait by the full 50 ms
spin interval.

## Outcome

Client-side acquisition waits no longer depend on wall-clock adjustments, while
Redis server-time Lua semantics for lease expiry remain unchanged.

## Verification

- `./gradlew :bluetape4k-leader-redis-lettuce:compileKotlin :bluetape4k-leader-redis-lettuce:compileTestKotlin --no-build-cache --stacktrace`
- `./gradlew :bluetape4k-leader-redis-lettuce:test --tests 'io.bluetape4k.leader.lettuce.internal.MonotonicDeadlineTest' --no-build-cache --stacktrace`
  - 5 passing
- `./gradlew :bluetape4k-leader-redis-lettuce:test --tests 'io.bluetape4k.leader.lettuce.LettuceLeaderGroupElectionTest.maxLeaders 동시 점유 + 모두 minLease 보유 - 추가 client 는 실패한다' --no-build-cache --stacktrace`
  - 1 passing
- `./gradlew :bluetape4k-leader-redis-lettuce:test --no-build-cache --stacktrace`
  - First post-review run had one time-sensitive group minLease failure.
  - Immediate targeted rerun and full module rerun passed.
  - Final full module result: 212 passing

## Future Guidance

For Redis backends, keep lease/expiry decisions in Redis server time. Use
monotonic JVM time only for local retry budgets, elapsed durations, and wait
deadlines.
