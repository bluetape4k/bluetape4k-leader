# Issue #427 autoExtend backend benchmark - 2026-06-05

Issue #427 adds focused `kotlinx-benchmark` rows for the remaining README-documented
single-leader `autoExtend` backends outside the Redis slice covered by #422:
Local and MongoDB. The benchmark code lives in the normal `benchmark` source set
and the measured artifact was built by Gradle's generated JMH jar task.

Use this report for same-machine comparison only. It is not a release-grade
performance claim.

## Caveats

- Redis `autoExtend` coverage remains in
  [`2026-06-01-issue-422-redis-lease-extension.md`](./2026-06-01-issue-422-redis-lease-extension.md).
- The plain `runIfLeader` rows use a 60 second lease and a fast action to
  measure normal execution versus `autoExtend` enablement overhead.
- The `runIfLeaderWithRenewalWindow` rows use a 90 ms lease and 45 ms action
  dwell so the auto-extension path has a renewal window. Compare these rows only
  within the same method because the dwell time dominates.
- The MongoDB rows use the repository Testcontainers launcher. Short-window
  MongoDB scores have broad error bounds, especially for suspend quick rows, so
  they do not justify a production tuning issue by themselves.
- The generated Gradle benchmark tasks run the full benchmark matrix. This run
  built the official JMH jar with Gradle and then used the JMH include pattern to
  keep the raw output focused on the new #427 rows.
- `@LeaderGroupElection` is not benchmarked because group-election
  auto-extension is not supported yet. Backends not documented in the README as
  supporting single-leader `autoExtend` are outside this issue's benchmark scope.

## Environment

| Field | Value |
|---|---|
| Date | 2026-06-05 |
| Host | Apple M4 Pro, 12 CPUs |
| OS | macOS 26.5.1 arm64 |
| JDK | Oracle GraalVM 25.0.3 |
| Gradle | 9.5.1 |
| Kotlin | 2.3.20 |
| kotlinx-benchmark | 0.4.17 |
| JMH | 1.37 |
| Docker | Testcontainers via `unix:///Users/debop/.colima/default/docker.sock`; server 29.2.1, Ubuntu 24.04.4 LTS, 3905 MB |
| Warmup | 2 iterations, 1 second each |
| Measurement | 3 iterations, 1 second each |
| Forks | 1 |
| Threads | 1 |

## Commands

```bash
./gradlew :benchmark:tasks --all --no-daemon
./gradlew :benchmark:compileBenchmarkKotlin --no-daemon --no-configuration-cache
./gradlew :benchmark:benchmarkBenchmarkJar --no-daemon --no-configuration-cache --rerun-tasks
java -jar benchmark/build/benchmarks/benchmark/jars/benchmark-benchmark-jmh-0.4.0-JMH.jar '.*AutoExtendBackendLeaderElectorBenchmark.*' -bm thrpt -tu s -f 1 -wi 2 -i 3 -w 1s -r 1s -rf json -rff docs/benchmarks/2026-06-05-issue-427-autoextend-backends-throughput.json
java -jar benchmark/build/benchmarks/benchmark/jars/benchmark-benchmark-jmh-0.4.0-JMH.jar '.*AutoExtendBackendLeaderElectorBenchmark.*' -bm avgt -tu us -f 1 -wi 2 -i 3 -w 1s -r 1s -rf json -rff docs/benchmarks/2026-06-05-issue-427-autoextend-backends-average-time.json
```

Machine-readable source artifacts:

- [`2026-06-05-issue-427-autoextend-backends-throughput.json`](./2026-06-05-issue-427-autoextend-backends-throughput.json)
- [`2026-06-05-issue-427-autoextend-backends-average-time.json`](./2026-06-05-issue-427-autoextend-backends-average-time.json)

## Blocking Local and MongoDB API

Higher is better for throughput. Lower is better for average time.

| Scenario | Mode | Throughput (ops/s) | Average time (us/op) | Notes |
|---|---|---:|---:|---|
| `runIfLeader` | local-normal | 2,395,400.193 +/- 501,076.856 | 0.426 +/- 0.219 | 60s lease, fast action |
| `runIfLeader` | local-auto-extend | 805,517.783 +/- 1,278,895.802 | 1.237 +/- 2.269 | Shared watchdog start/close overhead visible |
| `runIfLeader` | mongo-normal | 971.090 +/- 544.247 | 5,774.991 +/- 28,639.740 | MongoDB Testcontainer |
| `runIfLeader` | mongo-auto-extend | 692.798 +/- 749.379 | 2,569.192 +/- 33,179.484 | Error bound too wide for tuning |
| `runIfLeaderWithRenewalWindow` | local-normal | 21.511 +/- 0.547 | 46,273.157 +/- 1,105.062 | 90ms lease, 45ms action dwell |
| `runIfLeaderWithRenewalWindow` | local-auto-extend | 21.577 +/- 3.122 | 46,154.705 +/- 2,389.850 | Dwell dominates |
| `runIfLeaderWithRenewalWindow` | mongo-normal | 16.198 +/- 2.870 | 57,592.652 +/- 14,277.831 | 90ms lease, 45ms action dwell |
| `runIfLeaderWithRenewalWindow` | mongo-auto-extend | 16.552 +/- 15.388 | 55,941.229 +/- 16,045.389 | Error bound overlaps normal row |

## Suspend Local and MongoDB API

| Scenario | Mode | Throughput (ops/s) | Average time (us/op) | Notes |
|---|---|---:|---:|---|
| `runIfLeader` | local-normal | 868,702.969 +/- 143,615.007 | 1.168 +/- 0.429 | Coroutine local baseline |
| `runIfLeader` | local-auto-extend | 388,941.209 +/- 188,261.017 | 2.549 +/- 1.169 | Shared watchdog start/close overhead visible |
| `runIfLeader` | mongo-normal | 171.671 +/- 496.698 | 6,693.307 +/- 15,305.281 | Noisy MongoDB suspend row |
| `runIfLeader` | mongo-auto-extend | 240.190 +/- 2,241.840 | 5,954.376 +/- 37,242.530 | Error bound too wide for tuning |
| `runIfLeaderWithRenewalWindow` | local-normal | 21.496 +/- 0.945 | 46,579.372 +/- 1,339.338 | 90ms lease, 45ms action dwell |
| `runIfLeaderWithRenewalWindow` | local-auto-extend | 21.502 +/- 2.185 | 46,742.978 +/- 4,988.328 | Dwell dominates |
| `runIfLeaderWithRenewalWindow` | mongo-normal | 17.352 +/- 8.027 | 61,080.897 +/- 22,853.647 | 90ms lease, 45ms action dwell |
| `runIfLeaderWithRenewalWindow` | mongo-auto-extend | 17.678 +/- 5.739 | 55,882.592 +/- 11,014.145 | Error bound overlaps normal row |

## Decision

No production optimization follow-up was opened. The new rows close the
benchmark coverage gap for README-supported Local and MongoDB single-leader
`autoExtend`, while the fresh MongoDB evidence remains too noisy for a narrow
tuning issue. Redis remains covered by #422, and unsupported group-election or
undocumented backend combinations should be tracked as separate API/support
work before adding benchmark rows.

## Verification

- `./gradlew :benchmark:tasks --all --no-daemon`
- `./gradlew :benchmark:compileBenchmarkKotlin --no-daemon --no-configuration-cache`
- `./gradlew :benchmark:benchmarkBenchmarkJar --no-daemon --no-configuration-cache --rerun-tasks`
- Focused JMH throughput run saved to `docs/benchmarks/2026-06-05-issue-427-autoextend-backends-throughput.json`
- Focused JMH average-time run saved to `docs/benchmarks/2026-06-05-issue-427-autoextend-backends-average-time.json`
