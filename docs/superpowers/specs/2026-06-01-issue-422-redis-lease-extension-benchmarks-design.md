# Issue 422 Redis Lease Extension Benchmark Design

## Context

Issue #422 asks the benchmark module to distinguish Redis baseline
`runIfLeader` cost from lease-extension/watchdog cost for Lettuce and Redisson.
The current benchmark tables contain only one Lettuce row and one Redisson row
for blocking and suspend APIs.

Current source inspection shows that `RedissonLeaderElector` and
`RedissonSuspendLeaderElector` always acquire locks with an explicit
`leaseTime`; this disables Redisson's native watchdog. Redisson `autoExtend`
therefore uses the shared `LeaderLeaseAutoExtender`, not Redisson's native
watchdog path.

## Goal

Add focused Redis benchmark rows that compare normal and `autoExtend` election
paths for Lettuce and Redisson, without changing production behavior. Record the
unsupported native Redisson watchdog path as a documented constraint.

## Scope

- Add a focused blocking benchmark class for Redis lease-extension modes.
- Add a focused suspend benchmark class for the same modes.
- Compare:
  - `lettuce-normal`
  - `lettuce-auto-extend`
  - `redisson-normal`
  - `redisson-auto-extend`
- Keep benchmark options aligned with existing cross-backend rows:
  `waitTime = 1s`, `leaseTime = 60s`, and `minLeaseTime = 0`.
- Reuse `RedisServer.Launcher.redis` and local Lettuce/Redisson client cleanup
  patterns from the existing benchmark classes.
- Store raw throughput and average-time JSON under `docs/benchmarks/`.
- Update English and Korean benchmark READMEs with result tables and caveats.

## Non-Goals

- No production optimization or behavior change.
- No new public API for Redisson native watchdog benchmarking.
- No unsupported `autoExtend + minLeaseTime` behavior change.
- No CI timing benchmark execution; CI compile coverage is enough.

## Acceptance Criteria

- `:benchmark:compileBenchmarkKotlin` compiles the new benchmark classes.
- Full default benchmark tasks run locally and produce throughput and
  average-time results for the new Redis lease-extension rows.
- README benchmark docs explain that higher throughput is better and lower
  average time is better.
- README benchmark docs state that Redisson native watchdog is not represented
  by the current public elector because the implementation uses explicit
  `leaseTime`.
- Raw JSON result files are committed under `docs/benchmarks/`.
- No production code optimization is made without benchmark evidence.

## Step 2-R Local 7-Tier Review

| Tier | Scope | P0 | P1 | P2 | P3 | Evidence |
|---|---|---:|---:|---:|---:|---|
| Security | Redis benchmark clients | 0 | 0 | 0 | 0 | Testcontainers Redis uses local ephemeral endpoints only. |
| Ops/SRE | Resource lifecycle | 0 | 0 | 0 | 0 | Existing Lettuce/Redisson close patterns are reused. |
| Structural | Module boundary | 0 | 0 | 0 | 0 | Changes stay in non-published `benchmark` docs/source. |
| Kotlin/API | Leader option contracts | 0 | 0 | 0 | 0 | Uses existing `LeaderElectionOptions.autoExtend`. |
| Tests/types | Compile and smoke behavior | 0 | 0 | 0 | 0 | Setup performs `runIfLeader` smoke checks before measurement. |
| Performance | Benchmark validity | 0 | 0 | 0 | 1 | Same-machine results are comparative, not release-grade claims. |
| Docs/evidence | Watchdog caveat | 0 | 0 | 0 | 0 | Redisson native watchdog gap is documented as unsupported. |

P0/P1: 0. Gate closed.
