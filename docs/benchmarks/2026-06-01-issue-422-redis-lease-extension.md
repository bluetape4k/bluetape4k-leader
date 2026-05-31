# Issue #422 Redis lease extension benchmark - 2026-06-01

Issue #422 added focused `kotlinx-benchmark` rows for Redis leader election
lease-extension behavior. The goal was to compare Lettuce and Redisson normal
execution against bluetape4k's shared `autoExtend` lease extender without
changing production code.

Use this report for same-machine comparison only. It is not a release-grade
performance claim.

## Caveats

- The blocking and suspend rows use the same Redis Testcontainers launcher.
- The plain `runIfLeader` rows use a 60 second lease and a fast action to
  measure normal execution versus `autoExtend` enablement overhead.
- The `runIfLeaderWithRenewalWindow` rows use a 90 ms lease and 45 ms action
  dwell so the auto-extension path has a renewal window. Compare those rows only
  within the same method because the dwell time dominates.
- `redisson-auto-extend` uses bluetape4k's shared `LeaderLeaseAutoExtender`.
  Redisson native watchdog mode is not represented because the current Redisson
  electors always acquire locks with an explicit `leaseTime`.
- The score deltas are within broad JMH error bounds. Do not use this run as
  evidence for production optimization.

## Environment

| Field | Value |
|---|---|
| Date | 2026-06-01 |
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

## Command

```bash
./gradlew :benchmark:benchmarkBenchmark :benchmark:benchmarkAverageTimeBenchmark --no-daemon --no-configuration-cache --rerun-tasks
```

Machine-readable source artifacts:

- [`2026-06-01-issue-422-redis-lease-extension-throughput.json`](./2026-06-01-issue-422-redis-lease-extension-throughput.json)
- [`2026-06-01-issue-422-redis-lease-extension-average-time.json`](./2026-06-01-issue-422-redis-lease-extension-average-time.json)

## Blocking Redis API

Higher is better for throughput. Lower is better for average time.

| Scenario | Mode | Throughput (ops/s) | Average time (us/op) | Notes |
|---|---|---:|---:|---|
| `runIfLeader` | lettuce-normal | 1,454.484 ± 812.222 | 696.879 ± 261.682 | 60s lease, fast action |
| `runIfLeader` | lettuce-auto-extend | 1,432.206 ± 673.228 | 674.570 ± 76.338 | Shared auto extender enabled |
| `runIfLeader` | redisson-normal | 1,392.344 ± 156.055 | 721.043 ± 46.545 | 60s lease, fast action |
| `runIfLeader` | redisson-auto-extend | 1,379.041 ± 380.447 | 739.360 ± 42.259 | Shared auto extender, not native watchdog |
| `runIfLeaderWithRenewalWindow` | lettuce-normal | 18.858 ± 2.142 | 52,787.594 ± 13,078.335 | 90ms lease, 45ms action dwell |
| `runIfLeaderWithRenewalWindow` | lettuce-auto-extend | 19.191 ± 3.072 | 52,012.788 ± 14,742.520 | Renewal-window comparison row |
| `runIfLeaderWithRenewalWindow` | redisson-normal | 18.540 ± 4.514 | 52,495.646 ± 13,993.629 | 90ms lease, 45ms action dwell |
| `runIfLeaderWithRenewalWindow` | redisson-auto-extend | 19.150 ± 6.465 | 51,782.799 ± 5,184.910 | Shared auto extender, not native watchdog |

## Suspend Redis API

| Scenario | Mode | Throughput (ops/s) | Average time (us/op) | Notes |
|---|---|---:|---:|---|
| `runIfLeader` | lettuce-normal | 1,442.249 ± 772.451 | 668.478 ± 280.073 | 60s lease, fast action |
| `runIfLeader` | lettuce-auto-extend | 1,413.118 ± 434.324 | 693.538 ± 206.127 | Shared auto extender enabled |
| `runIfLeader` | redisson-normal | 1,382.143 ± 173.134 | 718.507 ± 233.162 | 60s lease, fast action |
| `runIfLeader` | redisson-auto-extend | 1,363.848 ± 134.125 | 728.479 ± 177.469 | Shared auto extender, not native watchdog |
| `runIfLeaderWithRenewalWindow` | lettuce-normal | 18.757 ± 6.519 | 53,820.084 ± 30,715.585 | 90ms lease, 45ms action dwell |
| `runIfLeaderWithRenewalWindow` | lettuce-auto-extend | 18.876 ± 0.844 | 52,182.685 ± 17,376.505 | Renewal-window comparison row |
| `runIfLeaderWithRenewalWindow` | redisson-normal | 18.603 ± 7.860 | 53,558.941 ± 19,665.787 | 90ms lease, 45ms action dwell |
| `runIfLeaderWithRenewalWindow` | redisson-auto-extend | 19.214 ± 8.932 | 51,883.433 ± 6,959.355 | Shared auto extender, not native watchdog |

## Decision

No production optimization was made. The benchmark establishes coverage for the
currently exposed public elector behavior and documents that native Redisson
watchdog benchmarking needs a separate API or production behavior change before
it can be measured honestly.

## Verification

- `./gradlew :benchmark:compileBenchmarkKotlin --no-daemon`
- `./gradlew :benchmark:benchmarkBenchmark :benchmark:benchmarkAverageTimeBenchmark --no-daemon --no-configuration-cache --rerun-tasks`
