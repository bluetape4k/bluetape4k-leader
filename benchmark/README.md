# bluetape4k-leader benchmark

[한국어](./README.ko.md) | English

This non-published module contains comparable `kotlinx-benchmark` suites for
the leader election backends. The JVM runner is JMH, and the benchmark source
set lives under `benchmark/src/benchmark/kotlin`.

Use these results for same-machine before/after comparisons. They are not
release-grade performance claims.

## Benchmark Command

```bash
./gradlew :benchmark:benchmarkBenchmark :benchmark:benchmarkAverageTimeBenchmark --no-configuration-cache --rerun-tasks
```

The 2026-05-21 baseline was collected with one fork, one thread, two warmup
iterations, and three one-second measurement iterations. Full environment and
caveats are recorded in
[`docs/benchmarks/2026-05-21-leader-cross-backend-baseline.md`](../docs/benchmarks/2026-05-21-leader-cross-backend-baseline.md).

## Charts

Remote backend charts exclude the local and H2 rows so the distributed backend
differences remain visible.

![Leader benchmark remote throughput](../docs/images/readme-charts/leader-benchmark-remote-throughput-chart-01.svg)

![Leader benchmark remote latency](../docs/images/readme-charts/leader-benchmark-remote-latency-chart-01.svg)

Issue #329 also records a history-recorder before/after comparison from the
same benchmark harness.

![Leader history recorder self-improve throughput](../docs/images/readme-charts/leader-history-self-improve-throughput-chart-01.svg)

## Latest Self-Improve Result

Issue #329 optimized the history-recorder sanitization fast path without
changing the benchmark harness. The same throughput command improved the local
history rows:

| Benchmark | Baseline (ops/s) | After (ops/s) | Delta |
|---|---:|---:|---:|
| `HistoryRecorder.blockingInMemoryAcquireComplete` | 5,601,881.043 | 20,018,125.709 | +257.35% |
| `HistoryRecorder.blockingNoopAcquireComplete` | 7,642,848.188 | 62,740,146.724 | +720.90% |
| `HistoryRecorder.suspendInMemoryAcquireComplete` | 4,843,511.108 | 11,441,889.888 | +136.23% |
| `HistoryRecorder.suspendNoopAcquireComplete` | 5,257,310.052 | 23,153,305.712 | +340.40% |

Details:
[`docs/benchmarks/2026-05-21-issue-329-leader-history-recorder-self-improve.md`](../docs/benchmarks/2026-05-21-issue-329-leader-history-recorder-self-improve.md).

## Cross-Backend Results

Higher is better for throughput. Lower is better for average time.

### Blocking API

| Backend | Throughput (ops/s) | Average time (us/op) | Notes |
|---|---:|---:|---|
| local | 2,204,166.553 ± 387,424.052 | 0.445 ± 0.052 | In-process baseline |
| exposed-jdbc-h2 | 20,138.374 ± 59,295.930 | 49.943 ± 162.508 | Local H2 SQL layer baseline |
| hazelcast | 1,457.277 ± 213.303 | 693.926 ± 61.127 | Testcontainers-backed remote backend |
| redisson | 1,354.629 ± 2,657.106 | 715.517 ± 217.899 | Testcontainers-backed Redis backend |
| lettuce | 1,054.204 ± 11,495.384 | 703.769 ± 153.427 | Testcontainers-backed Redis backend |
| mongo | 934.619 ± 691.550 | 1,105.806 ± 87.387 | Testcontainers-backed remote backend |
| zookeeper | 760.439 ± 1,079.874 | 1,252.265 ± 1,393.136 | Testcontainers-backed remote backend |

### Suspend API

| Backend | Throughput (ops/s) | Average time (us/op) | Notes |
|---|---:|---:|---|
| local | 793,107.864 ± 193,258.001 | 1.250 ± 0.374 | Coroutine bridge baseline |
| exposed-r2dbc-h2 | 6,393.060 ± 18,208.172 | 162.539 ± 440.562 | Local H2 R2DBC layer baseline |
| lettuce | 1,458.073 ± 240.569 | 648.530 ± 311.462 | Testcontainers-backed Redis backend |
| redisson | 1,395.999 ± 248.707 | 713.728 ± 121.088 | Testcontainers-backed Redis backend |
| hazelcast | 1,393.962 ± 693.802 | 701.224 ± 136.723 | Testcontainers-backed remote backend |
| mongo | 829.311 ± 666.735 | 3,334.853 ± 61,304.680 | Noisy row; repeat before tuning |
| zookeeper | 721.758 ± 938.116 | 1,250.279 ± 947.488 | Testcontainers-backed remote backend |

## Local Core Rows

These rows remain the original 2026-05-21 cross-backend baseline. Use the
latest self-improve section above for issue #329 after numbers.

| Benchmark | Throughput (ops/s) | Average time (us/op) |
|---|---:|---:|
| `LocalLeader.blockingRunIfLeader` | 2,250,949.108 ± 167,049.822 | 0.451 ± 0.263 |
| `LocalLeader.asyncOnlyRunIfLeader` | 2,230,952.540 ± 248,386.525 | 0.447 ± 0.121 |
| `LocalLeader.completableFutureRunIfLeader` | 2,231,412.162 ± 324,642.886 | 0.445 ± 0.080 |
| `LocalLeader.suspendRunIfLeader` | 838,923.760 ± 388,344.058 | 1.172 ± 0.243 |
| `LocalLeader.virtualThreadRunIfLeader` | 138,705.240 ± 7,476.129 | 7.377 ± 1.244 |
| `HistoryRecorder.blockingNoopAcquireComplete` | 7,356,503.438 ± 2,672,535.544 | 0.129 ± 0.001 |
| `HistoryRecorder.blockingInMemoryAcquireComplete` | 5,828,846.244 ± 233,849.435 | 0.171 ± 0.014 |
| `HistoryRecorder.suspendNoopAcquireComplete` | 5,300,097.780 ± 186,734.921 | 0.164 ± 0.007 |
| `HistoryRecorder.suspendInMemoryAcquireComplete` | 4,784,646.339 ± 1,302,210.407 | 0.206 ± 0.032 |

## Interpretation

- Treat throughput as the canonical ranking metric; average time is auxiliary.
- Compare distributed backends against distributed backends. Do not rank local
  H2 rows against Redis, Hazelcast, ZooKeeper, or MongoDB as distributed systems.
- The local rows isolate framework and API overhead before any network or
  storage round trip.
- Benchmark setup performs a smoke `runIfLeader` check before measurement, so a
  failed infrastructure connection does not become a false fast-path row.
- Repeat noisy rows, especially suspend MongoDB, before optimizing against them.

## Benchmark Classes

| Class | Scenario |
|---|---|
| `BackendLeaderElectorBenchmark` | Blocking `runIfLeader` across local, Redis, Exposed JDBC H2, MongoDB, Hazelcast, and ZooKeeper |
| `SuspendBackendLeaderElectorBenchmark` | Suspend `runIfLeader` across local, Redis, Exposed R2DBC H2, MongoDB, Hazelcast, and ZooKeeper |
| `LocalLeaderElectorBenchmark` | Local blocking, async, completable-future, suspend, and virtual-thread elector overhead |
| `HistoryRecorderBenchmark` | No-op and in-memory leader history recorder overhead |
