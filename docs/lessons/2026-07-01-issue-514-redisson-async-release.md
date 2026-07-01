# Issue 514 - Redisson Async Release Completion

## Context

Issue #514 exposed an ordering bug in Redisson async election paths. The returned `CompletableFuture` could complete from the user action while lock/permit release or min-lease update was still running as a side effect.

## Decision

Compose the caller-visible future with the release/update future. The outer future should complete only after the action and release/update paths have both completed. If the action failed, preserve that action failure after cleanup. If cleanup fails, log it deterministically.

## Test Guard

Use immediate retry tests without sleeps to prove the ordering contract. The first call completes, then the second call must acquire the same lock or group permit immediately. For bluetape4k concurrency-sensitive regressions, express repeated stress with `MultithreadingTester` instead of ad hoc `repeat` loops. Keep separate tests for synchronous action throws before future creation and failed futures after creation.

## Documentation Guard

When async completion ordering changes, update both module README locale files if they describe release, `minLeaseTime`, or async execution semantics. In this issue, `README.ko.md` also had stale Redisson `autoExtend` wording and was brought back in line with `README.md`.

## Outcome

Redisson single-lock and group async paths now wait for release/update before completing the returned future. This prevents immediate chained retries from observing false contention after `join()` or `get()` returns.

## Verification

- Targeted Redisson single/group tests: `46 passing`, `BUILD SUCCESSFUL in 14s`.
- Full Redisson module test: `./gradlew :bluetape4k-leader-redis-redisson:test --no-parallel`, `204 passing`, `BUILD SUCCESSFUL in 18s`.
- Compile warning repair: `Expression is unused` absent from the rerun log.
- Review gate: independent `code-reviewer` P0/P1 = 0; independent `verifier` repair lane P0/P1/P2/P3 = 0.
- `git diff --check`: pass.
