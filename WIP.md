# WIP - bluetape4k-leader

Snapshot: 2026-05-11 KST
Scope: open GitHub issues assigned to `debop`, created on or after 2026-01-01.

## Recently Completed

- **#73 watchdog / lease auto-extend** — PR #153 merged.
- **#151 Redis group minLeaseTime slot-token TTL** — PR #170 merged.
- **#79 PR 1 Core** — PR #178 merged: `LockAssert`, `LockExtender`, `LeaderLockHandle`, `ExtendOutcome`, `LockHandleElement`, `BackendErrorClassifier`, local backend contracts, and Spring AOP integration.
- **#79 PR 2 Lettuce** — PR #179 merged: Lettuce single/group ExtendDelegate integration, server-side TIME Lua for slot-token group extension, contract tests.
- **#79 PR 3 Redisson** — PR #182 merged: Redisson single/group ExtendDelegate integration, `updateLeaseTime`, owner/thread guards, contract tests.
- Governance/doc maintenance merged today: PR #180 lessons guidance, PR #181 Kover Nightly gates, PR #183 Dependabot baseline.
- **#37 leader-ktor** and **#36 examples** remain completed and should stay in CHANGELOG/history rather than active WIP.

## Current Direction

The active path is now the **#79 backend split** after Core, Lettuce, and Redisson have landed. Continue the remaining backends in small PRs, while keeping production reliability follow-ups visible:

1. Finish #79 backend support: MongoDB, Exposed JDBC, Exposed R2DBC, Hazelcast, ZooKeeper, then Ktor smoke.
2. Address watchdog reliability and capture-scope safety follow-ups created during the #79 review.
3. Resume Flux/Flow, state/audit, and multi-tenancy work only after the lease-extension foundation is stable across backends.

## Priority Queue

| Priority | Issue | Difficulty | Notes |
|---|---|---:|---|
| P0 | [#79](https://github.com/bluetape4k/bluetape4k-leader/issues/79) LockExtender / LockAssert backend split | XL | PR #1 Core, #2 Lettuce, #3 Redisson merged. Continue Mongo -> JDBC -> R2DBC -> Hazelcast -> ZooKeeper -> Ktor smoke. |
| P1 | [#173](https://github.com/bluetape4k/bluetape4k-leader/issues/173) watchdog graceful shutdown | M | Production reliability gap from #79 review; add lifecycle shutdown and scheduler failure handling. |
| P1 | [#174](https://github.com/bluetape4k/bluetape4k-leader/issues/174) watchdog thread pool sizing / HOL blocking | M | Prevent tick starvation for many concurrent leases. |
| P1 | [#74](https://github.com/bluetape4k/bluetape4k-leader/issues/74) Flux/Flow AOP support | L | Depends on settled lease extension semantics and backend coverage. |
| P2 | [#175](https://github.com/bluetape4k/bluetape4k-leader/issues/175) suspend capture ThreadLocal dispatcher-hop leak | M | Prefer coroutine context / ThreadContextElement semantics before more suspend backend work. |
| P2 | [#176](https://github.com/bluetape4k/bluetape4k-leader/issues/176) concurrent LockExtender stress tests | M | Adds AC-6 stress coverage for watchdog x explicit extend races. |
| P2 | [#68](https://github.com/bluetape4k/bluetape4k-leader/issues/68) election state API | M | Useful for metrics and operational visibility after lease semantics settle. |
| P2 | [#50](https://github.com/bluetape4k/bluetape4k-leader/issues/50) common audit contract | M | Define history semantics before durable audit implementations. |
| P2 | [#72](https://github.com/bluetape4k/bluetape4k-leader/issues/72) `@LeaderGroupElection` leaderId | M | Group API change; do after AOP baseline is stable. |
| P2 | [#39](https://github.com/bluetape4k/bluetape4k-leader/issues/39) use DB server time | M | Exposed backend-specific; depends on backend maturity. |
| P2 | [#42](https://github.com/bluetape4k/bluetape4k-leader/issues/42) multitenancy namespace | M | Valuable after lease and state contracts settle. |
| P3 | [#177](https://github.com/bluetape4k/bluetape4k-leader/issues/177) aspect identity shadowing polish | S | Readability/perf cleanup from #79 review. |
| P3 | [#145](https://github.com/bluetape4k/bluetape4k-leader/issues/145) Prometheus scrape runnable example | S | After state/metrics examples are stable. |
| P4 | [#38](https://github.com/bluetape4k/bluetape4k-leader/issues/38) lockAtLeastFor | M | Duplicate candidate; superseded by #77/#151 direction. |

## Dependency Map

```text
#73 watchdog / auto-extend (closed by PR #153)
#151 Redis group minLeaseTime slot-token TTL (closed by PR #170)
  -> #79 explicit lease extension API
      -> PR #178 Core/Spring/Local ✅
      -> PR #179 Lettuce ✅
      -> PR #182 Redisson ✅
      -> MongoDB / Exposed JDBC / Exposed R2DBC / Hazelcast / ZooKeeper / Ktor smoke remaining
      -> #173 watchdog graceful shutdown
      -> #174 watchdog pool sizing
      -> #175 suspend capture leak
      -> #176 concurrent stress tests
      -> #177 identity shadowing polish
  -> #74 Flux/Flow support

#68 state API
#50 audit contract
  -> #145 Prometheus runnable example
```

## WIP Limits

| Lane | Limit | Current next |
|---|---:|---|
| #79 backend split | 1 | Next backend PR after Redisson, starting with MongoDB unless evidence says otherwise. |
| Lease reliability | 1 | `#173` or `#174`; avoid mixing with backend PR unless required. |
| Coroutine safety/tests | 1 | `#175/#176` before broad suspend backend changes. |
| State/audit | 1 | `#68` or `#50` after lease foundation stabilizes. |
| Examples/integration | 1 | `#145` Prometheus example after state/audit lands. |

## Cleanup Actions

| Candidate | Action |
|---|---|
| `#38` | Close or mark duplicate after preserving any useful validation notes in the `#77/#151/#79` trail. |
| `#73/#77/#151` | Keep out of active WIP; they are represented by merged PR history and #79 follow-up work. |
| `#36` vs `bluetape4k-workshop #10` | `#36` closed in this repo as library examples landed. Workshop continues to own runnable scenario apps. |
