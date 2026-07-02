# Issue #523 History Recorder Observability Benchmarks

Issue #523 extends `HistoryRecorderBenchmark` so the recorder-only history path
has explicit observability rows. The benchmark compares no-op, in-memory, and
Micrometer-wrapped history recorders across blocking and suspend APIs, terminal
states, and metadata sizes.

## Scope

- Recorder implementations: no-op, in-memory, Micrometer with `SimpleMeterRegistry`.
- Terminal states: acquire + completed, acquire + failed.
- APIs: blocking `SafeLeaderHistoryRecorder`, suspend `SuspendSafeLeaderHistoryRecorder`.
- Metadata modes: `empty`, `small`, `large`.

The current history recorder contract records acquired, completed, and failed
events. It does not expose a skipped or not-elected terminal event, so skip
state coverage remains in the leader contention benchmarks from issue #521.
These rows are recorder-only rows. They do not include Spring advice dispatch
or backend lock acquisition; issue #522 covers Spring advice overhead.

## Commands

The primary Gradle benchmark tasks for this module are:

```bash
./gradlew :benchmark:benchmarkBenchmark :benchmark:benchmarkAverageTimeBenchmark --no-configuration-cache --rerun-tasks
```

For this issue, the full Gradle task would run the entire benchmark suite. The
raw issue evidence therefore uses the generated JMH jar with a class filter,
matching the existing benchmark evidence pattern in this repository:

```bash
./gradlew :benchmark:compileBenchmarkKotlin :benchmark:benchmarkBenchmarkJar --no-daemon --no-configuration-cache --console=plain

java -jar benchmark/build/benchmarks/benchmark/jars/benchmark-benchmark-jmh-0.5.0-JMH.jar \
  '.*HistoryRecorderBenchmark.*' \
  -bm thrpt -tu s -f 1 -wi 1 -i 2 -w 500ms -r 500ms \
  -rf json -rff docs/benchmarks/2026-07-02-issue-523-history-recorder-throughput.json

java -jar benchmark/build/benchmarks/benchmark/jars/benchmark-benchmark-jmh-0.5.0-JMH.jar \
  '.*HistoryRecorderBenchmark.*' \
  -bm avgt -tu us -f 1 -wi 1 -i 2 -w 500ms -r 500ms \
  -rf json -rff docs/benchmarks/2026-07-02-issue-523-history-recorder-average-time.json
```

Run shape: one fork, one thread, one 500 ms warmup iteration, and two 500 ms
measurement iterations. Use this as a same-machine comparable snapshot, not as
a release-grade performance claim.

Raw data:

- [`2026-07-02-issue-523-history-recorder-throughput.json`](./2026-07-02-issue-523-history-recorder-throughput.json)
- [`2026-07-02-issue-523-history-recorder-average-time.json`](./2026-07-02-issue-523-history-recorder-average-time.json)

Charts:

- [`leader-history-observability-throughput-chart-01.svg`](../images/readme-charts/leader-history-observability-throughput-chart-01.svg)
- [`leader-history-observability-latency-chart-01.svg`](../images/readme-charts/leader-history-observability-latency-chart-01.svg)

## Results

Higher is better for throughput. Lower is better for average time.

| API | Recorder | Terminal | Metadata | Throughput (ops/s) | Average time (us/op) |
|---|---|---|---|---:|---:|
| Blocking | Noop | Completed | empty | 369,591,201 | 0.0026 |
| Blocking | Noop | Completed | small | 62,153,981 | 0.0167 |
| Blocking | Noop | Completed | large | 3,951,395 | 0.2205 |
| Blocking | Noop | Failed | empty | 48,523,487 | 0.0180 |
| Blocking | Noop | Failed | small | 30,506,848 | 0.0323 |
| Blocking | Noop | Failed | large | 4,036,253 | 0.2597 |
| Blocking | In-memory | Completed | empty | 57,326,961 | 0.0182 |
| Blocking | In-memory | Completed | small | 18,875,312 | 0.0545 |
| Blocking | In-memory | Completed | large | 3,182,919 | 0.3123 |
| Blocking | In-memory | Failed | empty | 29,559,171 | 0.0343 |
| Blocking | In-memory | Failed | small | 14,255,133 | 0.0715 |
| Blocking | In-memory | Failed | large | 2,861,895 | 0.3491 |
| Blocking | Micrometer | Completed | empty | 53,053,805 | 0.0183 |
| Blocking | Micrometer | Completed | small | 18,031,147 | 0.0570 |
| Blocking | Micrometer | Completed | large | 3,160,459 | 0.3175 |
| Blocking | Micrometer | Failed | empty | 30,312,777 | 0.0331 |
| Blocking | Micrometer | Failed | small | 13,910,688 | 0.0709 |
| Blocking | Micrometer | Failed | large | 2,820,083 | 0.3946 |
| Suspend | Noop | Completed | empty | 33,042,164 | 0.0324 |
| Suspend | Noop | Completed | small | 23,477,969 | 0.0426 |
| Suspend | Noop | Completed | large | 4,000,090 | 0.2571 |
| Suspend | Noop | Failed | empty | 20,552,592 | 0.0446 |
| Suspend | Noop | Failed | small | 16,906,301 | 0.0635 |
| Suspend | Noop | Failed | large | 3,493,689 | 0.2834 |
| Suspend | In-memory | Completed | empty | 21,762,347 | 0.0461 |
| Suspend | In-memory | Completed | small | 11,173,330 | 0.0860 |
| Suspend | In-memory | Completed | large | 2,507,575 | 0.3637 |
| Suspend | In-memory | Failed | empty | 14,170,744 | 0.0725 |
| Suspend | In-memory | Failed | small | 10,270,385 | 0.1035 |
| Suspend | In-memory | Failed | large | 2,557,119 | 0.3860 |
| Suspend | Micrometer | Completed | empty | 21,581,894 | 0.0464 |
| Suspend | Micrometer | Completed | small | 11,671,342 | 0.0878 |
| Suspend | Micrometer | Completed | large | 2,786,518 | 0.3865 |
| Suspend | Micrometer | Failed | empty | 13,528,362 | 0.0739 |
| Suspend | Micrometer | Failed | small | 9,551,932 | 0.1031 |
| Suspend | Micrometer | Failed | large | 2,516,993 | 0.3844 |

## Interpretation

Metadata size dominates the recorder-only path. The no-op blocking completed
row moves from 0.0026 us/op with empty metadata to 0.2205 us/op with large
metadata because the safe recorder still sanitizes the record before handing it
to the sink. In-memory and Micrometer rows show the same shape: large metadata
cost is much more visible than the counter decorator itself.

For small metadata, the Micrometer wrapper is close to the in-memory recorder in
the completed path. Blocking completed rows are 18.9M ops/s for in-memory and
18.0M ops/s for Micrometer; suspend completed rows are 11.2M ops/s and 11.7M
ops/s respectively in this short run. Treat the small inversion in the suspend
completed row as run noise, not as evidence that Micrometer improves the path.

Failure rows are slower than completed rows because `recordFailed` extracts the
exception type and sanitizes/truncates the message. With small metadata,
blocking in-memory moves from 0.0545 us/op to 0.0715 us/op, while suspend
in-memory moves from 0.0860 us/op to 0.1035 us/op.

The benchmark uses `SimpleMeterRegistry` only. External metric backends,
histogram publication, push registries, scraping, and exporter I/O remain
outside this local recorder-only benchmark.
