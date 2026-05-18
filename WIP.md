# WIP - bluetape4k-leader

Snapshot: 2026-05-18 KST
Scope: open GitHub issues assigned to `debop`.
Open count: 18 issues.

## Refresh Notes

Verified with `gh` on 2026-05-18 KST.

- qmd was queried first for prior leader lessons, implementation plans, and follow-ups.
- Existing issues #304, #305, and #306 were unassigned; they are now assigned to `debop`.
- New issues registered from this audit:
  - [#308](https://github.com/bluetape4k/bluetape4k-leader/issues/308) - `bug: Mongo locks use wall-clock deadlines for tryLock timeout`
  - [#309](https://github.com/bluetape4k/bluetape4k-leader/issues/309) - `bug: Lettuce lock and slot acquisition use wall-clock deadlines`
- PR #307 (`chore: refresh WIP snapshot - 2026-05-18`) is already merged, so this file reflects the current post-merge GitHub state.

## Current Direction

The next work should stabilize lock timeout and cancellation behavior before
adding more backends or examples:

1. Fix Exposed JDBC cancellation and monotonic timeout issues (#304/#305/#306).
2. Apply the same monotonic deadline rule to Mongo and Lettuce acquisition loops (#308/#309).
3. Remove remaining suspend-to-blocking bridge delegates (#271) after correctness fixes.
4. Resume StateFlow, DynamoDB/etcd backends, and examples after the lock baseline is stable.

## Priority Queue

| Priority | Issue | Difficulty | Notes |
|---|---|---:|---|
| P1 | [#304](https://github.com/bluetape4k/bluetape4k-leader/issues/304) ExposedJdbc lock/elector swallows CancellationException | M | All `runCatching{}` sites in ExposedJdbc lock/elector operations must rethrow `CancellationException`. |
| P1 | [#305](https://github.com/bluetape4k/bluetape4k-leader/issues/305) ExposedJdbcLock.tryLock() NTP deadline bug | S | Replace wall-clock wait deadline with monotonic elapsed-time logic. |
| P1 | [#306](https://github.com/bluetape4k/bluetape4k-leader/issues/306) ExposedJdbcGroupLock.tryLock() same NTP deadline bug | S | Fix with #305 in one PR. |
| P1 | [#308](https://github.com/bluetape4k/bluetape4k-leader/issues/308) Mongo locks wall-clock tryLock deadlines | M | Blocking and suspend Mongo acquisition loops should use monotonic wait budgets; keep wall-clock only for persisted lease expiry. |
| P1 | [#309](https://github.com/bluetape4k/bluetape4k-leader/issues/309) Lettuce lock and slot wall-clock deadlines | M | Blocking/async/suspend lock and slot acquisition should share monotonic wait-budget handling. |
| P1 | [#271](https://github.com/bluetape4k/bluetape4k-leader/issues/271) replace runBlocking bridges in internal delegates | M | Remove suspend-to-blocking bridges after backend timeout/cancellation fixes. |
| P2 | [#222](https://github.com/bluetape4k/bluetape4k-leader/issues/222) LeaderStateFlow epic | L | Per-lock `StateFlow<LeaderState>` for coroutine-native consumers. |
| P2 | [#39](https://github.com/bluetape4k/bluetape4k-leader/issues/39) DB server time based locks | M | Exposed JDBC/R2DBC backend feature; related to clock-source correctness. |
| P2 | [#228](https://github.com/bluetape4k/bluetape4k-leader/issues/228) leader-dynamodb backend epic | L | Start after core lock semantics stabilize. |
| P2 | [#227](https://github.com/bluetape4k/bluetape4k-leader/issues/227) leader-etcd backend epic | L | Start after core lock semantics stabilize. |
| P3 | [#269](https://github.com/bluetape4k/bluetape4k-leader/issues/269) remove deprecated APIs after 0.1.0 GA | M | Maintenance after correctness lane. |
| P3 | [#270](https://github.com/bluetape4k/bluetape4k-leader/issues/270) promote StringTruncateSupport | S | Cross-repo support promotion. |
| P3 | [#287](https://github.com/bluetape4k/bluetape4k-leader/issues/287) sync README.ko.md | S | Documentation lane. |
| P3 | [#288](https://github.com/bluetape4k/bluetape4k-leader/issues/288) leader-ktor management KDoc | S | Documentation lane. |
| P3 | [#289](https://github.com/bluetape4k/bluetape4k-leader/issues/289) tenant namespacing README section | S | Documentation lane. |
| P4 | [#248](https://github.com/bluetape4k/bluetape4k-leader/issues/248) K3sServer Lease integration example | M | Example after backend correctness. |
| P4 | [#231](https://github.com/bluetape4k/bluetape4k-leader/issues/231) Kubernetes operator leader example | M | Example after backend correctness. |
| P4 | [#229](https://github.com/bluetape4k/bluetape4k-leader/issues/229) leader-coordinated rate limiting example | M | Example after backend correctness. |

## Dependency Map

```text
#304 ExposedJdbc CancellationException suppression
  -> fix before expanding leader-exposed-jdbc
  -> related: #271 runBlocking bridge removal

#305 ExposedJdbcLock monotonic timeout
#306 ExposedJdbcGroupLock monotonic timeout
  -> fix together

#308 MongoLock / MongoSuspendLock monotonic timeout
#309 Lettuce lock and slot monotonic timeout
  -> apply same deadline helper or pattern across blocking/async/suspend acquisition loops
  -> preserve backend wall-clock semantics only for persisted expiry timestamps

#222 LeaderStateFlow
  -> operational visibility after correctness baseline

#227 leader-etcd backend
#228 leader-dynamodb backend
  -> start after lock timeout/cancellation baseline
```

## WIP Limits

| Lane | Limit | Current next |
|---|---:|---|
| Correctness / cancellation | 1 | Start with `#304`; do not mix with feature work. |
| Timeout clock correctness | 1 | Fix `#305/#306`, then `#308/#309`. |
| Bridge refactor | 1 | `#271` after timeout/cancellation fixes. |
| New backend | 1 | `#227` or `#228` only after correctness baseline. |
| Examples/docs | 1 | Documentation and examples only as focused low-risk PRs. |
