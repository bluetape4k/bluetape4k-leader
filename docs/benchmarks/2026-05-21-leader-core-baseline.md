# leader-core benchmark baseline — 2026-05-21

This is a local developer-machine baseline for issue #326. Use it to compare
future changes on the same machine and JVM. Do not treat it as a release-grade
throughput claim.

## Caveats

- The coroutine benchmark uses `runBlocking {}` because JMH cannot invoke
  `suspend` benchmark methods directly; the result includes bridge cost.
- The virtual-thread benchmark includes virtual-thread submission and scheduling
  cost, not just local lock acquisition.
- The local elector benchmarks are uncontended, single-threaded hot-path
  measurements.
- JMH used compiler blackholes on this JVM; keep the same JVM and blackhole mode
  when comparing future runs.

## Environment

| Field | Value |
|---|---|
| Date | 2026-05-21 |
| Host | Apple M4 Pro, 12 CPUs, 48 GiB RAM |
| OS | macOS 26.5 arm64 |
| JDK | Oracle GraalVM 21.0.11 |
| Gradle | 9.5.1 |
| JMH | 1.37 |
| Warmup | 2 iterations, 1 second each |
| Measurement | 3 iterations, 1 second each |
| Forks | 1 |
| Threads | 1 |

## Command

```bash
./gradlew :bluetape4k-leader-core:jmh --no-configuration-cache
```

Report files:

- `leader-core/build/reports/jmh/human.txt`
- `leader-core/build/reports/jmh/results.json`

## Results

Higher is better for throughput. Lower is better for average time.

| Benchmark | Throughput (ops/us) | Average time (us/op) |
|---|---:|---:|
| HistoryRecorder.blockingNoopAcquireComplete | 7.459 ± 0.632 | 0.137 ± 0.018 |
| HistoryRecorder.blockingInMemoryAcquireComplete | 5.643 ± 0.537 | 0.180 ± 0.035 |
| HistoryRecorder.suspendNoopAcquireComplete | 5.775 ± 0.641 | 0.172 ± 0.017 |
| HistoryRecorder.suspendInMemoryAcquireComplete | 4.577 ± 0.213 | 0.218 ± 0.047 |
| LocalLeader.blockingRunIfLeader | 2.208 ± 0.056 | 0.451 ± 0.123 |
| LocalLeader.completableFutureRunIfLeader | 2.209 ± 0.629 | 0.452 ± 0.146 |
| LocalLeader.asyncOnlyRunIfLeader | 2.194 ± 0.662 | 0.459 ± 0.232 |
| LocalLeader.suspendRunIfLeader | 0.787 ± 0.566 | 1.247 ± 0.184 |
| LocalLeader.virtualThreadRunIfLeader | 0.140 ± 0.006 | 7.018 ± 1.371 |

## Observations

- Blocking and direct-executor `CompletableFuture` local paths are effectively
  tied at roughly `0.45 us/op` in this uncontended scenario.
- Coroutine local election is slower in this harness because it includes
  `runBlocking` plus `Mutex` acquisition/release.
- Virtual-thread local election is intentionally slower in this microbench
  because every operation submits work to the virtual-thread executor and waits
  for completion.
- In-memory history recorder overhead is below `0.25 us/op` in both blocking
  and suspend fixtures, well under the prior 1 ms hot-path target for in-memory
  recording.

## Next Work

- Issue #327 should add cross-backend benchmarks with Testcontainers and
  separate lock-backend setup cost from hot-path acquire/release cost.
- Issue #328 should publish README charts only after backend results are
  comparable.
- Issue #329 should start self-improve work from the benchmark rows above and
  the cross-backend results from issue #327.
