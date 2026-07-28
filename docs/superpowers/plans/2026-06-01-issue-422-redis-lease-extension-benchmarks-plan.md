# Issue 422 Redis Lease Extension Benchmark Plan

## 한국어 해설

이 문서는 `Issue 422 Redis Lease Extension Benchmark Plan`에 대한 설계 또는 실행 계획 기록입니다. 아래 원문 구조의 범위, 결정, 작업 순서, 검증 조건, 위험 및 후속 조치는 기록 보존을 위해 유지합니다. 검토자는 각 `Action`, `Expected DoD`, `Validation`, `Target files` 항목을 한국어 해설과 함께 읽고, 코드 식별자와 명령은 원문 그대로 취급해야 합니다.



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
