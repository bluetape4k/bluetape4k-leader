# Issue 514 - Redisson Async Release Review

## Scope

- Issue: #514 - Redisson async election futures complete before release finishes
- Module: `leader-redis-redisson`
- Files:
  - `RedissonLeaderElector.kt`
  - `RedissonLeaderGroupElector.kt`
  - `RedissonLeaderElectionTest.kt`
  - `RedissonLeaderGroupElectionTest.kt`
  - `leader-redis-redisson/README.md`
  - `leader-redis-redisson/README.ko.md`

## Review Result

P0/P1/P2/P3: 0.

The Redisson async single-lock and group paths now compose action completion with the backend release/update future before completing the caller-visible `CompletableFuture`. Action exceptions are preserved after release/update completes, while release/update failures are logged deterministically and do not hide the original action result.

## Evidence

- `RedissonLeaderElector.kt`: `actionFuture.handleAsync(...).thenCompose { releaseAndPropagate(...) }` waits for the release path before outer completion.
- `RedissonLeaderGroupElector.kt`: `actionFuture.handle(...).thenCompose { releaseAndPropagate(...) }` waits for audit cleanup plus permit release/update before outer completion.
- `RedissonLeaderElectionTest`: `MultithreadingTester` immediate retry after first future completion and sync-throw recovery cover the single-lock path.
- `RedissonLeaderGroupElectionTest`: `MultithreadingTester` immediate retry after first future completion and sync-throw recovery cover the group permit path.
- Existing failed-future tests cover action failure after future creation.
- `leader-redis-redisson/README.md` and `README.ko.md` document that async returned futures complete after release/update.

## Verification

- `./gradlew :bluetape4k-leader-redis-redisson:test --tests "*.RedissonLeaderElectionTest" --tests "*.RedissonLeaderGroupElectionTest" --no-parallel`: `46 passing`, `BUILD SUCCESSFUL in 14s`.
- `./gradlew :bluetape4k-leader-redis-redisson:test --no-parallel`: `204 passing`, `BUILD SUCCESSFUL in 18s`.
- Multithreading repair log evidence: `/tmp/issue-514-multithreading-targeted-test.log` and `/tmp/issue-514-multithreading-full-redisson-test.log`.
- Rerun log evidence: `/tmp/issue-514-full-redisson-test-rerun.log` line 1 records the exact command, line 233 records `204 passing`, and line 236 records `BUILD SUCCESSFUL in 19s`.
- `grep -n "Expression is unused\|w: file" /tmp/issue-514-full-redisson-test-rerun.log`: no matches.
- `git diff --check`: pass.

## Tooling Notes

- CodeGraph `detect_changes` and `get_impact_radius` ran but returned `0 changed function(s)` / `0 impacted nodes`; `traverse_graph` did not find the Redisson nodes. Treat that as stale Kotlin graph evidence and rely on direct diff review, tests, and independent reviewer lanes for this gate.
- Independent `code-reviewer` lane: P0/P1 = 0.
- Independent `verifier` repair lane: P0/P1/P2/P3 = 0.

## Concurrency Test Gate

The immediate retry regression tests use `MultithreadingTester().workers(4).rounds(5)` instead of an ad hoc `repeat` loop. Each worker round uses a distinct lock name and verifies the same local sequence: first async call completes, then the second async call immediately reacquires without sleeps. This keeps the #514 release-order assertion deterministic while still using the bluetape4k concurrency helper required by the code-patterns gate.
