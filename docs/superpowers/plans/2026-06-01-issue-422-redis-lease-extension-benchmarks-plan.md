# Issue 422 Redis Lease Extension Benchmark Plan

## Steps

1. Add blocking Redis lease-extension benchmark rows for Lettuce and Redisson.
2. Add suspend Redis lease-extension benchmark rows for Lettuce and Redisson.
3. Compile benchmark sources with `:benchmark:compileBenchmarkKotlin`.
4. Run the default throughput and average-time benchmark tasks locally.
5. Store raw JSON output under `docs/benchmarks/`.
6. Extract only the new Redis lease-extension rows for README tables.
7. Update English and Korean benchmark READMEs with result interpretation,
   raw JSON links, and the Redisson native watchdog caveat.
8. Add a concise lesson entry for the issue.
9. Run final validation: compile, benchmark tasks, `git diff --check`, and
   status review.

## Stop Condition

The work is done when the new benchmark rows compile, fresh throughput and
average-time results exist in committed JSON, README docs describe the supported
and unsupported paths, and the PR is created with CI passing.

## Step 3-R Local Review

| Perspective | Finding | Decision |
|---|---|---|
| Implementer | A separate focused class keeps the existing cross-backend table stable. | Add new benchmark classes instead of expanding the existing backend param sets. |
| Test engineer | Timing benchmarks are too heavy for CI but compile checks catch API drift. | Use local full run for evidence and keep CI compile-only. |
| Architect | Redisson native watchdog is not exposed by the current elector implementation. | Do not benchmark raw `RLock` because it would bypass the `LeaderElector` contract. |
| Delivery | Docs need measured numbers, not predicted deltas. | Update README tables only after fresh JSON exists. |

P0/P1: 0. Plan is ready for implementation.
