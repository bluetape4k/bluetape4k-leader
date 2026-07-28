# Issue #271 계획: Coroutine-Native Suspend Extend Delegates

## 한국어 해설

이 문서는 `Issue #271 계획: Coroutine-Native Suspend Extend Delegates`에 대한 설계 또는 실행 계획 기록입니다. 아래 원문 구조의 범위, 결정, 작업 순서, 검증 조건, 위험 및 후속 조치는 기록 보존을 위해 유지합니다. 검토자는 각 `Action`, `Expected DoD`, `Validation`, `Target files` 항목을 한국어 해설과 함께 읽고, 코드 식별자와 명령은 원문 그대로 취급해야 합니다.



## Ordered Tasks

1. Add `SuspendExtendDelegate` in `leader-core/internal`.
   - Required methods: `extendSuspend(Duration)` and `isHeldSuspend()`.
   - Sync `extend(Duration)` fallback returns
     `ExtendOutcome.BackendError(UnsupportedOperationException)` and never calls
     `runBlocking`.
   - Sync `isHeld()` fallback returns `false`.
2. Add a suspend-aware `LeaderLeaseAutoExtender.start` overload:
   - signature keeps the sync overload order:
     `start(enabled, leaseTime, delegate: SuspendExtendDelegate, classifier = null)`;
   - use the existing `ScheduledThreadPoolExecutor` for cadence;
   - dispatch each tick with `CoroutineScope(SupervisorJob() + Dispatchers.Default).launch`;
   - do not call `runBlocking` in the overload;
   - keep one in-flight tick per watchdog;
   - keep R2 skip and the same error-classifier behavior as the sync overload;
   - `close()` cancels future scheduling immediately and lets an in-flight
     suspend extend finish before cancelling the private job.
3. Add core tests proving:
   - suspend watchdog calls `extendSuspend`;
   - sync fallback on a suspend delegate does not bridge;
   - R2 skip prevents suspend backend calls;
   - close cancels further suspend ticks.
   - `LockExtender.extendActiveLockDetailedSuspend` updates
     `lastExtendDeadline` only on `Extended`.
4. Convert all production `*Suspend*ExtendDelegate` implementations in Lettuce,
   MongoDB, Redisson, Hazelcast, and Exposed R2DBC to implement
   `SuspendExtendDelegate` and remove sync `runBlocking` bridges.
   Keep suspend elector delegate variables statically typed as
   `SuspendExtendDelegate` when passing to `LeaderLeaseAutoExtender.start`.
5. Verify ZooKeeper suspend delegates already have no `runBlocking` bridge and
   leave their disabled-watchdog session behavior unchanged.
6. Add or adjust focused backend tests where compile coverage is not enough.
7. Run verification:
   - `./gradlew :bluetape4k-leader-core:test`
   - `./gradlew :bluetape4k-leader-core:test :bluetape4k-leader-redis-lettuce:compileKotlin :bluetape4k-leader-redis-redisson:compileKotlin :bluetape4k-leader-mongodb:compileKotlin :bluetape4k-leader-hazelcast:compileKotlin :bluetape4k-leader-exposed-r2dbc:compileKotlin`
   - `rg -n "runBlocking|: ExtendDelegate|import io\\.bluetape4k\\.leader\\.internal\\.ExtendDelegate" ... -g '*Suspend*ExtendDelegate.kt'`.
8. Run code review gates, write a lesson, commit, push, and open the PR.

## Validation Notes

- Testcontainers-backed backend tests must run sequentially, not in parallel.
- If full backend test suites are too slow, run compile plus focused contract
  tests first, then expand only on failure or risky coverage gaps.
- IntelliJ diagnostics should be attempted after edits. If the worktree is not
  indexed, record the gap and rely on Gradle compile/tests.

## Advisor Gate Status

- Claude Code CLI advisor is required by the full design workflow.
- If local Claude quota remains unavailable, record the failed artifact and
  stop before implementation unless the user explicitly overrides that gate.
- Advisor retry succeeded:
  `.omx/artifacts/claude-issue-271-spec-plan-review-20260522-020135-retry.md`.
  Accepted P0/P1 edits were folded into this plan before implementation.
