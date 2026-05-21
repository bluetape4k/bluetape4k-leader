# Issue #329 leader history recorder self-improve - 2026-05-21

Issue #329 used the benchmark-driven self-improve loop to tune the
`LeaderHistoryRecorderSupport` sanitization hot path. The accepted candidate
keeps the benchmark harness and sealed benchmark artifacts unchanged and only
removes avoidable sanitizer allocation for already-safe history records.

Use this report for same-machine before/after comparison only. It is not a
release-grade performance claim.

## Goal

- Primary metric: improve
  `HistoryRecorderBenchmark.blockingInMemoryAcquireComplete` throughput by at
  least 1%.
- Guard metrics: keep
  `HistoryRecorderBenchmark.suspendInMemoryAcquireComplete` and
  `HistoryRecorderBenchmark.blockingNoopAcquireComplete` within 5% of baseline.
- Stop condition: accept the first candidate that satisfies the metric gate, or
  stop after three iterations, two failed benchmark runs, or plateau.

## Sealed Files

The self-improve run treated these paths as sealed and verified they were not
changed:

- `benchmark/build.gradle.kts`
- `benchmark/src/benchmark/`
- `benchmark/src/benchmark/resources/logback-test.xml`
- `docs/benchmarks/2026-05-21-leader-cross-backend-baseline.md`
- `docs/benchmarks/2026-05-21-leader-core-baseline.md`

## Command

```bash
./gradlew :benchmark:benchmarkBenchmark --no-configuration-cache --rerun-tasks
```

`kotlinx-benchmark` writes a generated runner settings file before invoking
JMH, so ad-hoc `--args 'HistoryRecorderBenchmark'` filtering was not used for
the accepted comparison.

Machine-readable source artifacts:

- Baseline:
  `benchmark/build/reports/benchmarks/main/2026-05-21T22.47.37.838587/benchmark.json`
- Candidate:
  `benchmark/build/reports/benchmarks/main/2026-05-21T22.53.57.264618/benchmark.json`

## Result

Higher is better. The accepted candidate passed the primary and guard metrics
on iteration 1.

| Benchmark | Baseline (ops/s) | After (ops/s) | Delta |
|---|---:|---:|---:|
| `HistoryRecorder.blockingInMemoryAcquireComplete` | 5,601,881.043 | 20,018,125.709 | +257.35% |
| `HistoryRecorder.blockingNoopAcquireComplete` | 7,642,848.188 | 62,740,146.724 | +720.90% |
| `HistoryRecorder.suspendInMemoryAcquireComplete` | 4,843,511.108 | 11,441,889.888 | +136.23% |
| `HistoryRecorder.suspendNoopAcquireComplete` | 5,257,310.052 | 23,153,305.712 | +340.40% |

## Decision

Accepted: replace regex-based sanitization with a safe fast path:

- return the original string when it contains no control, C1 control, line
  separator, or paragraph separator characters;
- return the original metadata map when key count, key lengths, value lengths,
  and unsafe-character checks already satisfy recorder limits;
- allocate a sanitized copy only when truncation or replacement is required.

Rejected alternatives:

- Change Base58 or random-token generation. That would affect non-local backend
  semantics and token entropy instead of isolating the history-recorder hot path.
- Tune the benchmark harness. The self-improve gate required comparable
  before/after evidence, so benchmark files stayed sealed.

## Verification

- `./gradlew :bluetape4k-leader-core:test --tests 'io.bluetape4k.leader.history.LeaderHistoryRecorderSupportTest' --no-configuration-cache`
- `./gradlew :benchmark:benchmarkBenchmark --no-configuration-cache --rerun-tasks`
- `/Users/debop/.codex/skills/bluetape4k-self-improve/scripts/validate-sealed.sh --repo .`

## Remaining Risk

- This was a short single-machine throughput run. Re-run longer profiles before
  making release-note performance claims.
- Average-time mode was not re-run for this self-improve iteration because
  throughput was the primary gate.
- Remote backend rows in the same benchmark command are noisy and unrelated to
  this local history-recorder optimization.
