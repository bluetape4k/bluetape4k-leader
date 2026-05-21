# leader cross-backend benchmark baseline - 2026-05-21

This is a local developer-machine baseline for issue #327. It uses
`kotlinx-benchmark` as the Gradle benchmark frontend and JMH as the JVM backend.
Use it for same-machine before/after comparisons, not as a release-grade
performance claim.

## Caveats

- The benchmark source set lives under `benchmark/src/benchmark/kotlin`.
- The JVM runner is JMH through `kotlinx-benchmark`; benchmark classes still use
  JMH annotations and `Blackhole`.
- Testcontainers-backed rows include Docker network and client round-trip cost.
- H2 rows are local in-memory database baselines, not distributed lock backend
  claims.
- Suspend rows include the benchmark harness bridge cost where the measured API
  is invoked from non-suspend JMH methods.
- Benchmark setup performs a smoke `runIfLeader` check before measurement so
  failed infrastructure connections do not turn into false fast-path rows.
- The suspend MongoDB row was noisy in this short run; repeat before using it
  for a tuning decision.

## Environment

| Field | Value |
|---|---|
| Date | 2026-05-21 |
| Host | Apple M4 Pro, 12 CPUs, 48 GiB RAM |
| OS | macOS 26.5 arm64 |
| JDK | Oracle GraalVM 21 |
| Gradle | 9.5.1 |
| kotlinx-benchmark | 0.4.17 |
| JMH | 1.37 |
| Docker | Colima, Docker server 29.2.1, Ubuntu 24.04.4 LTS, 3905 MB |
| Warmup | 2 iterations, 1 second each |
| Measurement | 3 iterations, 1 second each |
| Forks | 1 |
| Threads | 1 |
| Logging | INFO root / `io.bluetape4k`, selected noisy libraries WARN |

## Command

```bash
./gradlew :benchmark:benchmarkBenchmark :benchmark:benchmarkAverageTimeBenchmark --no-configuration-cache --rerun-tasks
```

Machine-readable source artifacts:

- `benchmark/build/reports/benchmarks/main/2026-05-21T13.57.04.377621/benchmark.json`
- `benchmark/build/reports/benchmarks/averageTime/2026-05-21T13.57.04.377621/benchmark.json`

## Cross-Backend Results

Higher is better for throughput. Lower is better for average time.

### Blocking API

| Backend | Throughput (ops/s) | Average time (us/op) |
|---|---:|---:|
| local | 2,204,166.553 ± 387,424.052 | 0.445 ± 0.052 |
| exposed-jdbc-h2 | 20,138.374 ± 59,295.930 | 49.943 ± 162.508 |
| hazelcast | 1,457.277 ± 213.303 | 693.926 ± 61.127 |
| redisson | 1,354.629 ± 2,657.106 | 715.517 ± 217.899 |
| lettuce | 1,054.204 ± 11,495.384 | 703.769 ± 153.427 |
| mongo | 934.619 ± 691.550 | 1,105.806 ± 87.387 |
| zookeeper | 760.439 ± 1,079.874 | 1,252.265 ± 1,393.136 |

### Suspend API

| Backend | Throughput (ops/s) | Average time (us/op) |
|---|---:|---:|
| local | 793,107.864 ± 193,258.001 | 1.250 ± 0.374 |
| exposed-r2dbc-h2 | 6,393.060 ± 18,208.172 | 162.539 ± 440.562 |
| lettuce | 1,458.073 ± 240.569 | 648.530 ± 311.462 |
| redisson | 1,395.999 ± 248.707 | 713.728 ± 121.088 |
| hazelcast | 1,393.962 ± 693.802 | 701.224 ± 136.723 |
| mongo | 829.311 ± 666.735 | 3,334.853 ± 61,304.680 |
| zookeeper | 721.758 ± 938.116 | 1,250.279 ± 947.488 |

## Local Core Rows

| Benchmark | Throughput (ops/s) | Average time (us/op) |
|---|---:|---:|
| LocalLeader.blockingRunIfLeader | 2,250,949.108 ± 167,049.822 | 0.451 ± 0.263 |
| LocalLeader.asyncOnlyRunIfLeader | 2,230,952.540 ± 248,386.525 | 0.447 ± 0.121 |
| LocalLeader.completableFutureRunIfLeader | 2,231,412.162 ± 324,642.886 | 0.445 ± 0.080 |
| LocalLeader.suspendRunIfLeader | 838,923.760 ± 388,344.058 | 1.172 ± 0.243 |
| LocalLeader.virtualThreadRunIfLeader | 138,705.240 ± 7,476.129 | 7.377 ± 1.244 |
| HistoryRecorder.blockingNoopAcquireComplete | 7,356,503.438 ± 2,672,535.544 | 0.129 ± 0.001 |
| HistoryRecorder.blockingInMemoryAcquireComplete | 5,828,846.244 ± 233,849.435 | 0.171 ± 0.014 |
| HistoryRecorder.suspendNoopAcquireComplete | 5,300,097.780 ± 186,734.921 | 0.164 ± 0.007 |
| HistoryRecorder.suspendInMemoryAcquireComplete | 4,784,646.339 ± 1,302,210.407 | 0.206 ± 0.032 |

## Observations

- The central `benchmark/` module now gives one comparable command for local
  core, blocking backend, and suspend backend rows.
- Local hot paths are three orders of magnitude faster than Docker-backed
  distributed backend rows; future optimization should separate local API
  overhead from backend round-trip cost.
- Redis Lettuce, Redis Redisson, and Hazelcast are close in this short
  single-threaded hot-path run.
- Exposed H2 rows are fast because the storage is local in-memory H2. They are
  useful for SQL layer overhead but should not be ranked against Redis,
  Hazelcast, ZooKeeper, or MongoDB as distributed systems.
- Suspend MongoDB needs repeated runs or a longer measurement profile before
  ranking.

## Next Work

- Issue #328 should generate README charts from the two JSON files above.
- Issue #329 self-improve work should use throughput as the primary metric and
  average time as auxiliary latency evidence.
- For tuning candidates, keep the same command, fork count, JVM, Docker runtime,
  and logging level.
