# Issue #271 Design: Coroutine-Native Suspend Extend Delegates

**Issue**: #271
**Scope**: `leader-core`, suspend backend extend delegates
**Workflow**: Full Design

## Context

`ExtendDelegate` is the internal SPI shared by `LeaderLockHandle.Real` and
`LeaderLeaseAutoExtender`. It currently supports both sync and suspend extension
through one interface. Several suspend-native backends implement the sync
`extend()` and `isHeld()` methods by calling `runBlocking` around suspend lock
operations.

The issue body listed MongoDB and Redisson delegates, but current source shows
the same bridge pattern in Lettuce, MongoDB, Redisson, Hazelcast, and Exposed
R2DBC suspend delegates. Treating only two modules would leave the same
architecture smell in sibling modules.

## Goals

- Add a coroutine-native internal delegate contract for suspend backends.
- Let `LeaderLeaseAutoExtender` run suspend delegate ticks without `runBlocking`.
- Remove `runBlocking` from suspend extend delegates in production source.
- Preserve R2 watchdog skip semantics through the existing
  `lastExtendDeadline` reference.
- Preserve existing public APIs for electors, `LockExtender`, and annotation
  users.

## Non-Goals

- Do not remove startup-time `runBlocking` in Spring Boot bean initialization;
  that was reviewed separately under #263.
- Do not remove demo/test `runBlocking` usages.
- Do not change lock acquisition, release, or backend TTL semantics.
- Do not add new public application-facing APIs.

## Proposed Contract

Add an internal `SuspendExtendDelegate` next to `ExtendDelegate`.

- It extends `ExtendDelegate` to keep `LeaderLockHandle.Real` source-compatible.
- It requires `extendSuspend(lockAtMostFor)` and `isHeldSuspend()`.
- Its sync `extend()` fallback returns `ExtendOutcome.BackendError` with an
  `UnsupportedOperationException` instead of bridging with `runBlocking`.
- Its sync `isHeld()` fallback returns `false`.

Add a suspend-aware `LeaderLeaseAutoExtender.start(...)` overload that accepts
`SuspendExtendDelegate` and launches each tick in a coroutine scope from the
existing `ScheduledThreadPoolExecutor`. The overload must not call
`runBlocking`. The scheduler still owns cadence and keeps
`configure(watchdogThreads = ...)` behavior. `asyncExtend` is ignored for this
overload because every suspend tick is already dispatched without blocking the
watchdog scheduler thread.

Close policy:

- `close()` marks the watchdog closed and cancels future scheduling.
- If no tick is in flight, it cancels the private `SupervisorJob` immediately.
- If a suspend extend is in flight, it is allowed to finish the atomic backend
  operation; the job is cancelled in the tick `finally` block after completion.
  This avoids mid-transaction cancellation differences across Redisson, MongoDB,
  R2DBC, and Hazelcast.

The sync fallback on `SuspendExtendDelegate.extend()` is intentionally a
misuse path. It returns `ExtendOutcome.BackendError(UnsupportedOperationException)`
with a clear message and never bridges with `runBlocking`. `isHeld()` returns
`false` for the same reason. Suspend code must call
`LockExtender.extendActiveLockDetailedSuspend`, which uses `extendSuspend`.

## Affected Production Delegates

- `LettuceSuspendLockExtendDelegate`
- `LettuceSuspendSlotExtendDelegate`
- `MongoSuspendLockExtendDelegate`
- `MongoSuspendSlotExtendDelegate`
- `RedissonSuspendLockExtendDelegate`
- `RedissonSuspendSemaphoreExtendDelegate`
- `HazelcastSuspendLockExtendDelegate`
- `HazelcastSuspendSlotExtendDelegate`
- `ExposedR2dbcSuspendLockExtendDelegate`
- `ExposedR2dbcSuspendSlotExtendDelegate`

ZooKeeper suspend delegates already avoid `runBlocking`; verify this remains
true but do not change their disabled-watchdog/session-based behavior.

## Acceptance Criteria

- Suspend delegate production files above contain no `runBlocking` import or
  bridge.
- The new suspend `LeaderLeaseAutoExtender` overload contains no `runBlocking`.
- Suspend electors with `autoExtend=true` use suspend-native watchdog ticks.
- No production call site reaches sync `extend()` / `isHeld()` on a
  `SuspendExtendDelegate`; sync misuse returns a visible backend error / false
  rather than blocking.
- `LockExtender.extendActiveLockDetailedSuspend` still updates
  `lastExtendDeadline` only after `Extended`.
- R2 watchdog skip tests cover suspend delegates as well as sync delegates.
- A suspend watchdog test uses a delegate whose sync `extend()` fails if called,
  proving the watchdog dispatches through `extendSuspend`.
- `LockExtender.extendActiveLockDetailedSuspend` is covered end to end for a
  suspend delegate and proves `lastExtendDeadline` updates only on `Extended`.
- Targeted module tests pass for `leader-core` and touched backend modules.
- `rg "runBlocking" .../internal/*Suspend*ExtendDelegate.kt` and
  `rg "import kotlinx.coroutines.runBlocking" .../internal/*Suspend*ExtendDelegate.kt`
  return no production suspend delegate bridge hits.

## Risks

- Overload resolution could accidentally select the sync `ExtendDelegate`
  overload if a delegate is typed too broadly. The implementation will keep
  delegate variables statically typed as `SuspendExtendDelegate` in suspend
  electors and add tests that prove suspend watchdog calls `extendSuspend`, not
  sync `extend`.
- Closing a suspend watchdog must stop future ticks without interrupting an
  in-flight backend atomic extend. The overload will cancel the private
  `SupervisorJob` only after any running tick completes.

## Advisor Review Notes

### Claude Code Opus Advisor

Artifact: `.omx/artifacts/claude-issue-271-spec-plan-review-20260522-020135-retry.md`

- P0 accepted: specify scheduler/coroutine dispatch model and forbid moving
  `runBlocking` into `LeaderLeaseAutoExtender`.
- P0 accepted: document sync fallback behavior and add production call-site/test
  coverage for suspend delegates.
- P1 accepted: pin overload resolution, close/in-flight policy,
  `configure()` interaction, and ZooKeeper verification.
