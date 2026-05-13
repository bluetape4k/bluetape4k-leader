# leader-id testFixture API Freeze — PR1

> **Frozen at: PR #<TBD> merge to develop** (이 문서는 PR 번호가 확정되면 업데이트)

## 목적

PR1이 merge되면 PR2-6 worker branch들이 testFixture API를 의존하게 됩니다.
testFixture 또는 factory 변경이 발생하면 coordinator 통보 → 모든 open PR rebase → 별도 follow-up PR로 freeze doc 업데이트.

## PR2-6 Worker Branching 절차

PR1 merge 후:
```bash
git fetch origin
git checkout -b feat/leader-id-pr2 origin/develop
# 또는 worktree 방식:
git worktree add .worktrees/feat-leader-id-pr2 -b feat/leader-id-pr2 origin/develop
```

commit SHA 의존 없음 — `origin/develop` HEAD 기준.

## Frozen API: AbstractLeaderIdContractTest (testFixtures)

### Abstract Methods

| Method | Signature |
|--------|-----------|
| createGroupElector | `abstract fun createGroupElector(maxLeaders: Int): LeaderGroupElector` |
| createSingleElector | `abstract fun createSingleElector(): LeaderElector` |

### Contract Test Methods (partial list)

- `LeaderRunResult Elected carries leaderId from LeaderSlot AFTER backend migration`
- `default provider yields unique leaderId per call`
- `LeaderLease auditLeaderId equals LeaderSlot leaderId after acquire`
- `LeaderLease nodeId equals options nodeId after acquire`
- `with nodeId set, deprecated leaderId getter returns nodeId not auditLeaderId`
- `with nodeId null, deprecated leaderId getter returns auditLeaderId`
- `concurrent runIfLeader calls each have distinct leaderId`
- `bridge WARN counter is zero when backend overrides slot variants` (T82)
- `reentrant inner frame preserves auditLeaderId` (T51)
- `LeaderElectionInfo.validate() passes for backend-produced info` (T65)

## Frozen API: LeaderLockHandle.Real

### Constructor Positional Order

```
Real(
    identity: LockIdentity,       // 1
    token: String,                // 2
    acquiredAtNanos: Long,        // 3
    slotId: String? = null,       // 4
    acquiringThreadId: Long? = null, // 5
    reentryDepth: Int = 0,        // 6
    extendDelegate: ExtendDelegate, // 7
    auditLeaderId: String? = null, // 8 — END positional (NEW in PR1)
)
```

### Factory `real()` Positional Order

동일한 순서. `auditLeaderId` 는 마지막 named parameter (default null — backward compat).

## Frozen API: LeaderElectorBridgeLog Global Holder

| API | Description |
|-----|-------------|
| `companion fun global()` | 현재 global instance 반환 |
| `setGlobal(LeaderElectorBridgeLog)` | global 교체 — prev.dropped log.info |
| `droppedAuditCount(): Long` | slot bridge drop 횟수 |
| `droppedResultBridgeCount(): Long` | result bridge drop 횟수 |
| `warnOnBridgeUse(KClass<*>, LeaderSlot)` | slot bridge 사용 경고 (LRU throttle) |
| `warnOnResultBridgeUse(KClass<*>, LeaderSlot)` | result bridge 사용 경고 (LRU throttle) |

## Frozen API: LeaderRecorderContextDropLog Global Holder

| API | Description |
|-----|-------------|
| `companion fun global()` | 현재 global instance 반환 |
| `setGlobal(LeaderRecorderContextDropLog)` | global 교체 |
| `droppedCount(): Long` | context drop 횟수 |
| `warnOnDrop(KClass<*>, LeaderAopMetricsContext)` | drop 경고 (first-time per class) |

## Frozen API: LeaderAopMetricsContext.Identified

```kotlin
data class Identified(
    val leaderId: String,           // 1st — non-blank
    val leaderIdSource: LeaderIdSource, // 2nd
)
```

## 변경 발생 시

1. coordinator (PR author) 에게 즉시 통보
2. 모든 open PR2-6 worker rebase
3. 이 문서 업데이트 — 별도 follow-up PR (workspace branch protection 준수)
