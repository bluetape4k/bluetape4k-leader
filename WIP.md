# WIP - bluetape4k-leader

Snapshot: 2026-05-10 KST
Scope: open GitHub issues assigned to `debop`, created on or after 2026-01-01.

## Recently Completed (now in CHANGELOG `[Unreleased]`)

- **#37 leader-ktor** — Ktor 3.x integration (`LeaderElectionPlugin`,
  `Application.leaderScheduled`) — PR #164.
- **#36 leader examples** — six runnable examples covering every supported
  backend:
  - `examples/batch-scheduler` (Lettuce Redis) — PR #159
  - `examples/migration-gate` (Exposed JDBC) — PR #160
  - `examples/webhook-poller` (MongoDB) — PR #161
  - `examples/cache-warmer` (Hazelcast) — PR #162
  - `examples/tenant-aggregator` (Exposed R2DBC) — PR #163
  - `examples/ktor-app` (Ktor 3.x + Lettuce) — PR #166

## Current Direction

Examples and Ktor integration are now landed. The remaining open work is
centered on **lease safety**, **state/audit semantics**, and **multi-tenancy**.
`#145` (Prometheus runnable example) remains in the examples lane but is
single-issue rather than a phase.

## Priority Queue

| Priority | Issue | Difficulty | Notes |
|---|---|---:|---|
| P1 | [#73](https://github.com/bluetape4k/bluetape4k-leader/issues/73) watchdog / lease auto-extend | L | Addresses split-brain risk for long-running AOP work. |
| P1 | [#77](https://github.com/bluetape4k/bluetape4k-leader/issues/77) minLeaseTime backend TTL delegation | L | More concrete replacement for `#38`; align semantics before implementing both. |
| P1 | [#79](https://github.com/bluetape4k/bluetape4k-leader/issues/79) LockExtender / LockAssert | M | Builds on watchdog semantics and explicit extension behavior. |
| P1 | [#74](https://github.com/bluetape4k/bluetape4k-leader/issues/74) Flux/Flow AOP support | L | Depends on `#73`; streaming lease renewal must be solved first. |
| P2 | [#68](https://github.com/bluetape4k/bluetape4k-leader/issues/68) election state API | M | Useful for metrics and operational visibility. |
| P2 | [#50](https://github.com/bluetape4k/bluetape4k-leader/issues/50) common audit contract | M | Define history semantics before durable audit implementations. |
| P2 | [#72](https://github.com/bluetape4k/bluetape4k-leader/issues/72) `@LeaderGroupElection` leaderId | M | Group API change; do after AOP baseline is stable. |
| P2 | [#39](https://github.com/bluetape4k/bluetape4k-leader/issues/39) use DB server time | M | Exposed backend-specific; depends on backend maturity. |
| P2 | [#42](https://github.com/bluetape4k/bluetape4k-leader/issues/42) multitenancy namespace | M | Valuable after lease and state contracts settle. |
| P3 | [#145](https://github.com/bluetape4k/bluetape4k-leader/issues/145) Prometheus scrape runnable example | S | After state/metrics examples are stable. |
| P4 | [#38](https://github.com/bluetape4k/bluetape4k-leader/issues/38) lockAtLeastFor | M | Duplicate candidate; merge useful text into `#77`, then close as duplicate. |

## Dependency Map

```text
#73 watchdog / auto-extend
  -> #74 Flux/Flow support
  -> #79 explicit lease extension API

#38 lockAtLeastFor
#77 minLeaseTime backend TTL delegation
  -> keep #77 as implementation issue; close #38 as duplicate after migration

#68 state API
#50 audit contract
  -> #145 Prometheus runnable example
```

## WIP Limits

| Lane | Limit | Current next |
|---|---:|---|
| Lease safety | 1 | `#73`, then `#77/#79/#74`. |
| State/audit | 1 | `#68` or `#50`. |
| Examples/integration | 1 | `#145` Prometheus example after state/audit lands. |

## Cleanup Actions

| Candidate | Action |
|---|---|
| `#38` | Move any option-validation detail into `#77`, comment that `#77` supersedes it, then close `#38` as duplicate. |
| `#36` vs `bluetape4k-workshop #10` | `#36` closed in this repo as the six small library examples landed. Workshop continues to own runnable scenario apps. |
