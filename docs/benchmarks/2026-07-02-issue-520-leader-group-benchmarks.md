# Issue 520 Leader Group Benchmark Snapshot

Issue #520 adds benchmark coverage for group semaphore election paths. The new
benchmark classes cover blocking and suspend APIs with `maxLeaders` values 1,
2, and 8. This report records the quick `maxLeaders=2` chart snapshot used in
the README.

## Environment

- Date: 2026-07-02
- Host OS: macOS 26.5.1, build 25F80
- Java: Oracle GraalVM 21.0.11
- Gradle: 9.6.0
- Kotlin: 2.3.21
- Scope: same-machine developer snapshot, not release-grade performance data

## Commands

The benchmark source was compiled and packaged first:

```bash
./gradlew :benchmark:compileBenchmarkKotlin --no-configuration-cache --console=plain --warning-mode all
./gradlew :benchmark:benchmarkBenchmarkJar --no-daemon --no-configuration-cache --console=plain --rerun-tasks
```

The chart snapshot used short, non-forked JMH runs so Testcontainers-backed
backends could be compared quickly from the same process:

```bash
java -jar benchmark/build/benchmarks/benchmark/jars/benchmark-benchmark-jmh-0.5.0-JMH.jar \
  '.*LeaderGroupElectorBenchmark\.(freeSlotRunIfLeader|saturatedSkipRunIfLeader|mixedRunIfLeader).*' \
  -p maxLeaders=2 -bm thrpt -tu s -f 0 -wi 0 -i 1 -r 200ms \
  -rf json -rff docs/benchmarks/2026-07-02-issue-520-leader-group-throughput.json

java -jar benchmark/build/benchmarks/benchmark/jars/benchmark-benchmark-jmh-0.5.0-JMH.jar \
  '.*LeaderGroupElectorBenchmark\.(freeSlotRunIfLeader|saturatedSkipRunIfLeader|mixedRunIfLeader).*' \
  -p maxLeaders=2 -bm avgt -tu us -f 0 -wi 0 -i 1 -r 200ms \
  -rf json -rff docs/benchmarks/2026-07-02-issue-520-leader-group-average-time.json
```

JMH warns that `-f 0` is suitable only for debugging-style runs. Treat this file
as a chart and smoke-test record. Use a forked, warmed benchmark before making a
production tuning decision.

## Raw Data

- Throughput JSON: [`2026-07-02-issue-520-leader-group-throughput.json`](./2026-07-02-issue-520-leader-group-throughput.json)
- Average-time JSON: [`2026-07-02-issue-520-leader-group-average-time.json`](./2026-07-02-issue-520-leader-group-average-time.json)
- Throughput chart: [`leader-group-throughput-chart-01.svg`](../images/readme-charts/leader-group-throughput-chart-01.svg) / [`leader-group-throughput-chart-01.png`](../images/readme-charts/leader-group-throughput-chart-01.png)
- Average-time chart: [`leader-group-latency-chart-01.svg`](../images/readme-charts/leader-group-latency-chart-01.svg) / [`leader-group-latency-chart-01.png`](../images/readme-charts/leader-group-latency-chart-01.png)

## Result Table

Higher is better for throughput. Lower is better for average time.

| API | Scenario | Backend | Throughput (ops/s) | Average time (us/op) |
|---|---|---|---:|---:|
| Blocking | Free slot | local | 541,782 | 2.11 |
| Blocking | Free slot | exposed-jdbc-h2 | 2,320 | 439.3 |
| Blocking | Free slot | lettuce | 806.3 | 1,010 |
| Blocking | Free slot | redisson | 902.4 | 1,017 |
| Blocking | Free slot | mongo | 344.5 | 3,370 |
| Blocking | Free slot | zookeeper | 209.2 | 4,605 |
| Blocking | Mixed slots | local | 1,482,781 | 0.67 |
| Blocking | Mixed slots | exposed-jdbc-h2 | 152.5 | 5,915 |
| Blocking | Mixed slots | lettuce | 1,543 | 2,029 |
| Blocking | Mixed slots | redisson | 963.4 | 989.9 |
| Blocking | Mixed slots | mongo | 75.87 | 13,146 |
| Blocking | Mixed slots | zookeeper | 247.5 | 4,745 |
| Blocking | Saturated skip | local | 38.2 | 26,036 |
| Blocking | Saturated skip | exposed-jdbc-h2 | 38.01 | 26,065 |
| Blocking | Saturated skip | lettuce | 35.89 | 27,693 |
| Blocking | Saturated skip | redisson | 36.36 | 27,597 |
| Blocking | Saturated skip | mongo | 35.2 | 27,234 |
| Blocking | Saturated skip | zookeeper | 32.88 | 29,086 |
| Suspend | Free slot | local | 216,124 | 4.33 |
| Suspend | Free slot | lettuce | 1,315 | 770.2 |
| Suspend | Free slot | redisson | 919.3 | 1,062 |
| Suspend | Free slot | mongo | 297 | 3,298 |
| Suspend | Free slot | zookeeper | 211.8 | 4,348 |
| Suspend | Mixed slots | local | 296,157 | 3.39 |
| Suspend | Mixed slots | lettuce | 1,396 | 684.1 |
| Suspend | Mixed slots | redisson | 1,104 | 971.9 |
| Suspend | Mixed slots | mongo | 82.79 | 10,747 |
| Suspend | Mixed slots | zookeeper | 276.7 | 4,017 |
| Suspend | Saturated skip | local | 38.04 | 26,269 |
| Suspend | Saturated skip | lettuce | 36.34 | 27,447 |
| Suspend | Saturated skip | redisson | 24.69 | 44,784 |
| Suspend | Saturated skip | mongo | 36.03 | 26,808 |
| Suspend | Saturated skip | zookeeper | 32.05 | 31,188 |

## Interpretation

- Local and blocking H2 rows are framework/storage-shape baselines. They are
  preserved in the table but omitted from README charts because they obscure
  remote backend differences.
- Saturated-skip rows are dominated by the 25 ms wait-time path, so all
  backends cluster around roughly one operation per wait window.
- Lettuce and Redisson lead most free-slot and mixed-slot remote rows in this
  short snapshot. MongoDB remains slower and noisier for mixed slots.
- The chart uses a log scale to keep saturated rows visible beside free-slot
  and mixed-slot rows.
