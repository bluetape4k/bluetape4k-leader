# Mongo tryLock monotonic deadlines

## Context

Issue #308 reported that Mongo lock `tryLock` acquisition loops used wall-clock time
to decide retry deadlines. A system clock jump could shorten or extend the local
wait budget unexpectedly.

## Decision

Use a small Mongo-local `MonotonicDeadline` helper for blocking and suspend
`tryLock` retry budgets. Keep MongoDB lease expiry as wall-clock `Date` values
because `expireAt` is persisted and compared across clients by MongoDB.

Clamp randomized retry delays to the remaining monotonic budget and reject
non-positive max delay values at the helper boundary.

## Outcome

`MongoLock.tryLock` and `MongoSuspendLock.tryLock` now use `System.nanoTime()` for
local acquisition timeout accounting while preserving existing lease persistence
semantics.

## Verification

- `./gradlew :bluetape4k-leader-mongodb:compileKotlin :bluetape4k-leader-mongodb:compileTestKotlin --no-build-cache --stacktrace`
- `./gradlew :bluetape4k-leader-mongodb:test --tests 'io.bluetape4k.leader.mongodb.internal.MonotonicDeadlineTest' --no-build-cache --stacktrace`
- `./gradlew :bluetape4k-leader-mongodb:test --no-build-cache --stacktrace`
- Claude review: SHIP; a P2 helper precondition gap was fixed before commit.

## Future Guidance

Use monotonic JVM time only for local client wait budgets. Keep persisted MongoDB
lease timestamps wall-clock based unless the storage contract changes.
