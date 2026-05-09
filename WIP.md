# WIP - bluetape4k-leader

Snapshot: 2026-05-09 KST
Scope: open GitHub issues assigned to `debop`, created on or after 2026-01-01.
Open count: 13 issues.

## Current Direction

Completed implementation history belongs in `CHANGELOG.md` and merged PRs. The
active WIP is now centered on lease safety, state/audit semantics, and examples.

Do not start example-heavy work until long-running lease behavior is clear.

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
| P3 | [#36](https://github.com/bluetape4k/bluetape4k-leader/issues/36) leader examples | L | Wait for lease safety and state/audit decisions. |
| P3 | [#37](https://github.com/bluetape4k/bluetape4k-leader/issues/37) leader-ktor | M | Integration after API shape stabilizes. |
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

#36 examples
#37 Ktor integration
  -> after core semantics stabilize
```

## WIP Limits

| Lane | Limit | Current next |
|---|---:|---|
| Lease safety | 1 | `#73`, then `#77/#79/#74`. |
| State/audit | 1 | `#68` or `#50`. |
| Examples/integration | 0 until semantics settle | `#36/#37/#145` wait. |

## Cleanup Actions

| Candidate | Action |
|---|---|
| `#38` | Move any option-validation detail into `#77`, comment that `#77` supersedes it, then close `#38` as duplicate. |
| `#36` vs `bluetape4k-workshop #10` | Keep both only if this repo owns small library examples and workshop owns runnable scenario apps. |
