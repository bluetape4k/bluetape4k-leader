# Lesson: Migrate Concurrency Tests to MultithreadingTester / SuspendedJobTester

**Date**: 2026-05-16
**Issue**: #275
**PR**: #282
**Modules affected**: `leader-core`

## Summary

Migrated `LeaderGroupElectionStateTest.kt` from raw `Executors.newFixedThreadPool` + `CountDownLatch`
to `coroutineScope` + `async(Dispatchers.IO)` + `AtomicInteger` polling, consistent with the pattern
already established in `LocalSuspendLeaderGroupElectorTest.kt`.

## Scope

The initial audit found 33+ test files already using `MultithreadingTester` or `SuspendedJobTester`.
The only remaining raw-executor pattern was a single test in `LeaderGroupElectionStateTest.kt`.
`AsyncLeaderElectorContractTest.kt` and `AsyncLeaderGroupElectorContractTest.kt` had no raw thread
primitives to migrate.

## Migration Pattern

**Before** (raw thread pool):
```kotlin
val startLatch = CountDownLatch(2)
val holdLatch = CountDownLatch(1)
val executor = Executors.newFixedThreadPool(2)

repeat(2) {
    executor.submit {
        election.runIfLeader(lockName) {
            startLatch.countDown()
            holdLatch.await()
        }
    }
}
startLatch.await(2, TimeUnit.SECONDS)
// check state ...
holdLatch.countDown()
executor.shutdown()
executor.awaitTermination(3, TimeUnit.SECONDS)
```

**After** (coroutines + AtomicInteger polling):
```kotlin
val acquiredCount = AtomicInteger(0)
val holdLatch = CountDownLatch(1)  // required: action lambda is blocking, not suspend

coroutineScope {
    val jobs = List(2) {
        async(Dispatchers.IO) {
            election.runIfLeader(lockName) {
                acquiredCount.incrementAndGet()
                holdLatch.await()
            }
        }
    }
    while (acquiredCount.get() < 2) { delay(5.milliseconds) }
    // check state ...
    holdLatch.countDown()
    jobs.awaitAll()
}
```

## Why CountDownLatch Remains

`LocalLeaderGroupElector.runIfLeader` takes a `() -> T` blocking lambda — not a suspend lambda.
`CountDownLatch.await()` is the correct mechanism to hold the lock inside a blocking action.
This is consistent with the existing pattern in `LocalLeaderGroupElectionTest.kt`.

The key improvement is replacing `Executors.newFixedThreadPool` (unstructured, leak-prone) with
structured `coroutineScope { async(Dispatchers.IO) { } }`.

## Future Guidance

- `MultithreadingTester` / `SuspendedJobTester`: use for stress / fire-and-complete concurrency tests
- `coroutineScope + async(Dispatchers.IO)` + `AtomicInteger` polling: use for "check while holding" correctness tests with blocking electors
- `CountDownLatch.await()` inside a blocking action lambda is acceptable and expected
- `Executors.newFixedThreadPool` in tests: always replace with coroutines (structured) or `MultithreadingTester`
