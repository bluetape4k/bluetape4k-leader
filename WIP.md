# WIP - bluetape4k-leader

Snapshot: 2026-05-18 KST
Scope: open GitHub issues assigned to `debop`, created on or after 2026-01-01.

## Recently Completed

- **#79 LockExtender / LockAssert backend split** is complete through the 9-PR sequence:
  - PR #178 Core/Spring/Local.
  - PR #179 Lettuce.
  - PR #182 Redisson.
  - PR #190 MongoDB.
  - PR #192 Exposed JDBC.
  - PR #193 Exposed R2DBC.
  - PR #195 Hazelcast.
  - PR #196 ZooKeeper.
  - PR #197 Ktor smoke.
- #79 follow-up hygiene merged through PR #198/#201 lessons and PR #199/#200/#202/#203/#204 CI/coverage fixes.
- **#177 aspect identity shadowing polish** closed by PR #206.
- **#37 leader-ktor** and **#36 examples** remain completed and should stay in CHANGELOG/history rather than active WIP.

## Current Direction

The #79 backend implementation lane is no longer the active path. The next work should stabilize the lease-extension surface and make the public API easier to consume:

1. Address watchdog reliability follow-ups: graceful shutdown `#173` and scheduler/pool sizing `#174`.
2. Tighten coroutine capture and stress coverage with `#175/#176` before broadening reactive support.
3. Apply the public documentation language policy from `#205` to KDoc and contributor-facing artifacts.
4. Resume Flux/Flow, state/audit, and multi-tenancy work only after the lease-extension foundation remains stable.

## Priority Queue

| Priority | Issue | Difficulty | Notes |
|---|---|---:|---|
| P1 | [#304](https://github.com/bluetape4k/bluetape4k-leader/issues/304) ExposedJdbc lock/elector swallows CancellationException | M | All 6 `runCatching{}` sites in ExposedJdbc lock and elector must rethrow `CancellationException`; fix before expanding leader-exposed-jdbc. |
| P1 | [#305](https://github.com/bluetape4k/bluetape4k-leader/issues/305) ExposedJdbcLock.tryLock() NTP deadline bug | S | Replace `System.currentTimeMillis()` deadline with `System.nanoTime()` for monotonic clock guarantee. |
| P1 | [#306](https://github.com/bluetape4k/bluetape4k-leader/issues/306) ExposedJdbcGroupLock.tryLock() same NTP deadline bug | S | Same `currentTimeMillis()` → `nanoTime()` fix as #305; fix both in one PR. |
| P1 | [#173](https://github.com/bluetape4k/bluetape4k-leader/issues/173) watchdog graceful shutdown | M | Production reliability gap from #79 review; add lifecycle shutdown and scheduler failure handling. |
| P1 | [#174](https://github.com/bluetape4k/bluetape4k-leader/issues/174) watchdog thread pool sizing / HOL blocking | M | Prevent tick starvation for many concurrent leases. |
| P1 | [#74](https://github.com/bluetape4k/bluetape4k-leader/issues/74) Flux/Flow AOP support | L | Depends on settled lease extension semantics and backend coverage. |
| P2 | [#175](https://github.com/bluetape4k/bluetape4k-leader/issues/175) suspend capture ThreadLocal dispatcher-hop leak | M | Prefer coroutine context / ThreadContextElement semantics before more suspend backend work. |
| P2 | [#176](https://github.com/bluetape4k/bluetape4k-leader/issues/176) concurrent LockExtender stress tests | M | Adds AC-6 stress coverage for watchdog x explicit extend races. |
| P2 | [#205](https://github.com/bluetape4k/bluetape4k-leader/issues/205) public KDoc and contributor artifact English migration | L | Audit public KDoc and contributor-facing artifacts; keep README locale policy intact. |
| P2 | [#68](https://github.com/bluetape4k/bluetape4k-leader/issues/68) election state API | M | Useful for metrics and operational visibility after lease semantics settle. |
| P2 | [#50](https://github.com/bluetape4k/bluetape4k-leader/issues/50) common audit contract | M | Define history semantics before durable audit implementations. |
| P2 | [#72](https://github.com/bluetape4k/bluetape4k-leader/issues/72) `@LeaderGroupElection` leaderId | M | Group API change; do after AOP baseline is stable. |
| P2 | [#39](https://github.com/bluetape4k/bluetape4k-leader/issues/39) use DB server time | M | Exposed backend-specific; depends on backend maturity. |
| P2 | [#42](https://github.com/bluetape4k/bluetape4k-leader/issues/42) multitenancy namespace | M | Valuable after lease and state contracts settle. |
| P3 | [#145](https://github.com/bluetape4k/bluetape4k-leader/issues/145) Prometheus scrape runnable example | S | After state/metrics examples are stable. |
| P4 | [#38](https://github.com/bluetape4k/bluetape4k-leader/issues/38) lockAtLeastFor | M | Duplicate candidate; superseded by #77/#151/#79 direction. |

## Dependency Map

```text
#304 runCatching{} CancellationException suppression in ExposedJdbc lock/elector (P1)
  -> fix before expanding leader-exposed-jdbc
  -> related: #271 runBlocking bridges removal

#305 ExposedJdbcLock.tryLock() currentTimeMillis() → nanoTime() (P1)
#306 ExposedJdbcGroupLock.tryLock() same deadline bug (P1)
  -> fix #305 and #306 together in one PR
  -> monotonic clock required for reliable lock timeout in cloud environments

#73 watchdog / auto-extend (closed by PR #153)
#151 Redis group minLeaseTime slot-token TTL (closed by PR #170)
  -> #79 explicit lease extension API (closed through PR #178-#197)
      -> #173 watchdog graceful shutdown
      -> #174 watchdog pool sizing
      -> #175 suspend capture leak
      -> #176 concurrent stress tests
  -> #74 Flux/Flow support

#177 identity shadowing polish (closed by PR #206)

#205 public KDoc / contributor artifact language policy
  -> public API KDoc audit
  -> CHANGELOG / release note language consistency
  -> README locale synchronization only when README content changes

#68 state API
#50 audit contract
  -> #145 Prometheus runnable example
```

## WIP Limits

| Lane | Limit | Current next |
|---|---:|---|
| Lease reliability | 1 | `#173` or `#174`; avoid mixing with feature PRs unless required. |
| Coroutine safety/tests | 1 | `#175/#176` before broad suspend or reactive changes. |
| Public API documentation | 1 | `#205` as a focused KDoc/artifact migration lane. |
| State/audit | 1 | `#68` or `#50` after lease foundation stabilizes. |
| Examples/integration | 1 | `#145` Prometheus example after state/audit lands. |

## Cleanup Actions

| Candidate | Action |
|---|---|
| `#38` | Close or mark duplicate after preserving any useful validation notes in the `#77/#151/#79` trail. |
| `#73/#77/#151/#79/#177` | Keep out of active WIP; they are represented by merged PR history and follow-up issues. |
| `#36` vs `bluetape4k-workshop #10` | `#36` closed in this repo as library examples landed. Workshop continues to own runnable scenario apps. |
