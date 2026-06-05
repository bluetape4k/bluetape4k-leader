# Issue #414 MongoDB suspend benchmark repeat - 2026-06-05

Issue #414 repeated the noisy MongoDB suspend leader-election row against the
same-machine Lettuce, Redisson, and Hazelcast suspend baselines. The goal was to
separate repeatable backend overhead from short-window benchmark noise before
opening a tuning task.

Use this report for same-machine comparison only. It is not a release-grade
performance claim.

## Caveats

- Only `SuspendBackendLeaderElectorBenchmark.runIfLeader` was measured.
- The benchmark used the same one fork, one thread, two warmups, and three
  one-second measurement iterations as the existing cross-backend baseline.
- The generated Gradle `benchmarkBenchmark` and `benchmarkAverageTimeBenchmark`
  tasks were verified first, but runtime filtering through `--args` replaces the
  `kotlinx-benchmark` runner config path. The focused run therefore used the
  official JVM benchmark JAR produced by `:benchmark:benchmarkBenchmarkJar`.
- Each row starts its own Testcontainer in the JMH fork. Container startup is
  outside measured iterations, but Docker and local resource pressure can still
  affect short-window scores.
- JMH ran on GraalVM JDK 25 because that was the active shell `JAVA_HOME` for
  this repeat. Compare with the same JDK before making a before/after claim.

## Environment

| Field | Value |
|---|---|
| Date | 2026-06-05 |
| Host | Apple M4 Pro, 12 CPUs, 48 GiB RAM |
| OS | macOS 26.5.1 arm64 |
| JDK | Oracle GraalVM 25.0.3 |
| Gradle | 9.5.1 |
| kotlinx-benchmark | 0.4.17 |
| JMH | 1.37 |
| Docker | Colima, Docker server 29.2.1, Ubuntu 24.04.4 LTS, 3905 MB |
| Warmup | 2 iterations, 1 second each |
| Measurement | 3 iterations, 1 second each |
| Forks | 1 |
| Threads | 1 |

## Commands

Task-name verification:

```bash
./gradlew :benchmark:tasks --all --no-daemon
```

JMH JAR generation:

```bash
./gradlew :benchmark:benchmarkBenchmarkJar --no-daemon --no-configuration-cache --rerun-tasks
```

Throughput repeat, run once per `run` value from 1 to 3:

```bash
java -jar benchmark/build/benchmarks/benchmark/jars/benchmark-benchmark-jmh-0.4.0-JMH.jar \
  -foe true -bm thrpt -tu s -wi 2 -i 3 -w 1s -r 1s -f 1 \
  -p backend=lettuce,redisson,mongo,hazelcast \
  -rf json -rff docs/benchmarks/2026-06-05-issue-414-mongodb-suspend-throughput-run-${run}.json \
  '.*SuspendBackendLeaderElectorBenchmark.runIfLeader.*'
```

Average-time repeat, run once per `run` value from 1 to 3:

```bash
java -jar benchmark/build/benchmarks/benchmark/jars/benchmark-benchmark-jmh-0.4.0-JMH.jar \
  -foe true -bm avgt -tu us -wi 2 -i 3 -w 1s -r 1s -f 1 \
  -p backend=lettuce,redisson,mongo,hazelcast \
  -rf json -rff docs/benchmarks/2026-06-05-issue-414-mongodb-suspend-average-time-run-${run}.json \
  '.*SuspendBackendLeaderElectorBenchmark.runIfLeader.*'
```

Machine-readable source artifacts:

- [`2026-06-05-issue-414-mongodb-suspend-throughput-run-1.json`](./2026-06-05-issue-414-mongodb-suspend-throughput-run-1.json)
- [`2026-06-05-issue-414-mongodb-suspend-throughput-run-2.json`](./2026-06-05-issue-414-mongodb-suspend-throughput-run-2.json)
- [`2026-06-05-issue-414-mongodb-suspend-throughput-run-3.json`](./2026-06-05-issue-414-mongodb-suspend-throughput-run-3.json)
- [`2026-06-05-issue-414-mongodb-suspend-average-time-run-1.json`](./2026-06-05-issue-414-mongodb-suspend-average-time-run-1.json)
- [`2026-06-05-issue-414-mongodb-suspend-average-time-run-2.json`](./2026-06-05-issue-414-mongodb-suspend-average-time-run-2.json)
- [`2026-06-05-issue-414-mongodb-suspend-average-time-run-3.json`](./2026-06-05-issue-414-mongodb-suspend-average-time-run-3.json)

## Repeat Summary

Higher is better for throughput. Lower is better for average time.

| Backend | Throughput mean (ops/s) | Throughput run range (ops/s) | Mean JMH error (ops/s) | Average-time mean (us/op) | Average-time run range (us/op) | Mean JMH error (us/op) |
|---|---:|---:|---:|---:|---:|---:|
| lettuce | 1,459.312 | 1,437.426 - 1,485.930 | 752.101 | 709.290 | 675.345 - 741.277 | 221.520 |
| redisson | 1,408.395 | 1,395.184 - 1,431.931 | 883.032 | 716.850 | 689.188 - 731.004 | 490.830 |
| hazelcast | 1,406.936 | 1,378.012 - 1,441.799 | 695.930 | 734.549 | 700.048 - 762.327 | 716.699 |
| mongo | 634.467 | 302.035 - 896.383 | 3,030.521 | 3,796.573 | 1,324.228 - 5,348.705 | 25,693.233 |

## Per-Run Results

### Throughput

| Run | Lettuce (ops/s) | Redisson (ops/s) | Hazelcast (ops/s) | MongoDB (ops/s) |
|---:|---:|---:|---:|---:|
| 1 | 1,485.930 ± 726.394 | 1,398.071 ± 511.196 | 1,378.012 ± 287.418 | 896.383 ± 754.808 |
| 2 | 1,454.580 ± 241.711 | 1,431.931 ± 797.288 | 1,400.998 ± 236.957 | 704.984 ± 3,923.388 |
| 3 | 1,437.426 ± 1,288.199 | 1,395.184 ± 1,340.612 | 1,441.799 ± 1,563.414 | 302.035 ± 4,413.367 |

### Average Time

| Run | Lettuce (us/op) | Redisson (us/op) | Hazelcast (us/op) | MongoDB (us/op) |
|---:|---:|---:|---:|---:|
| 1 | 711.246 ± 269.694 | 689.188 ± 145.261 | 741.271 ± 890.111 | 1,324.228 ± 2,903.123 |
| 2 | 675.345 ± 83.277 | 731.004 ± 751.351 | 762.327 ± 591.689 | 4,716.788 ± 39,107.223 |
| 3 | 741.277 ± 311.589 | 730.357 ± 575.876 | 700.048 ± 668.298 | 5,348.705 ± 35,069.352 |

## Decision

No production tuning issue was opened from this repeat. MongoDB was consistently
slower than the Redis and Hazelcast suspend rows, but the score and error range
were too wide for a narrow optimization target:

- Throughput fell from 896 ops/s to 302 ops/s across three repeats.
- Average time moved from 1.3 ms/op to 5.3 ms/op across three repeats.
- JMH error for MongoDB exceeded the measured score in the noisier repeats.

Treat the current MongoDB suspend row as a noisy preview-backend comparison
point. Before changing production code, rerun a longer profile with the same JDK,
more measurement time, and optional MongoDB/client profiling so the bottleneck is
stable enough to isolate.

## Verification

- `./gradlew :benchmark:tasks --all --no-daemon`
- `./gradlew :benchmark:benchmarkBenchmarkJar --no-daemon --no-configuration-cache --rerun-tasks`
- JMH throughput repeat, 3 runs, raw JSON linked above.
- JMH average-time repeat, 3 runs, raw JSON linked above.
