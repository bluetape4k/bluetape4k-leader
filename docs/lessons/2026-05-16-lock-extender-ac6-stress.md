# Lesson: AC-6 LockExtender Concurrent Stress Tests

**Date**: 2026-05-16
**Issue**: #176
**PR**: TBD
**Modules affected**: `leader-core` (testFixtures)

## Summary

Added AC-6 concurrent extend stress tests to `AbstractSyncLockExtenderContractTest` and
`AbstractSuspendLockExtenderContractTest`. Tests verify that backend extend operations are
race-free when N leaders concurrently extend their own locks.

## Design Decision: Per-Worker Lock Names

`LockExtender` uses `ThreadLocal` (sync) / `CoroutineContext.Element` (suspend) to bind the
active lock handle to the calling thread/coroutine. Child threads spawned from inside
`runIfLeader` do NOT inherit the lock handle.

This means the "watchdog × user extend" concurrent scenario cannot be tested via the public
`LockExtender` API at the contract level. The AC-6 test instead verifies:

**N concurrent leaders, each extending their own slot** — this exercises the backend's
ability to handle concurrent atomic extend calls from independent clients simultaneously.

## SuspendedJobTester Semantics

`SuspendedJobTester.workers(N).rounds(M)`:
- `rounds(M)` = **M total invocations** (not N×M)
- `workers(N)` = max concurrency level

This differs from `MultithreadingTester.workers(N).rounds(M)` = N×M total invocations.

Assertion for suspend test: `rounds * extendsPerRound` (not `workers * rounds * extendsPerRound`).

## Tests Added

### Sync (AbstractSyncLockExtenderContractTest)
- `AC-6 concurrent extends race-free — N workers each extend their own lock`: `MultithreadingTester(8 workers × 10 rounds × 5 extends = 400)`
- `AC-6b sequential extends with random durations are all successful`: 20 sequential random-duration extends

### Suspend (AbstractSuspendLockExtenderContractTest)
- `AC-6 concurrent suspend extends race-free — N workers each extend their own lock`: `SuspendedJobTester(8 workers, 10 rounds × 5 extends = 50)`
- `AC-6b sequential suspend extends with random durations are all successful`: 20 sequential random-duration extends

## Verification

```
:leader-core:test — BUILD SUCCESSFUL (all 687+ tests pass)
```
