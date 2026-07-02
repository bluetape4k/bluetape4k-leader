# Issue #521 Leader Contention Benchmarks

Issue #521 adds focused contention and skip-path benchmarks for single-leader
electors. The benchmark separates existing-holder skip paths, parallel
contender paths, and mixed acquire/skip paths so the elected and skipped
outcomes are visible instead of being folded into one generic `runIfLeader`
row.

These results are a quick same-machine snapshot. They are useful for regression
tracking, README charts, and backend-shape inspection. They are not
release-grade performance claims.

## Scope

- Blocking remote/shared backends: Lettuce, Redisson, MongoDB, ZooKeeper, and
  Exposed JDBC H2 in the benchmark source.
- Suspend remote/shared backends: Lettuce, Redisson, MongoDB, ZooKeeper, and
  Exposed R2DBC H2 in the benchmark source.
- Local blocking and local suspend baselines are isolated in local-only classes
  because local lock instances only contend when the same elector instance is
  shared.
- README chart data uses `contenders=8`, `-f 0`, no warmup, and one 100 ms
  measurement iteration.
- Exposed H2 contention rows are implemented in the benchmark source, but the
  README chart snapshot focuses on remote backends plus local baseline rows.

## Charts

Higher is better for throughput. Lower is better for average time. Both charts
use a log scale because immediate local skip, remote immediate skip, and
positive-wait contention paths differ by several orders of magnitude.

![Leader contention throughput](../images/readme-charts/leader-contention-throughput-chart-01.png)

![Leader contention latency](../images/readme-charts/leader-contention-latency-chart-01.png)

## Commands

The benchmark jar was compiled first:

```bash
./gradlew :benchmark:compileBenchmarkKotlin --no-daemon --no-configuration-cache --console=plain
./gradlew :benchmark:benchmarkBenchmarkJar --no-daemon --no-configuration-cache --console=plain
```

The README snapshot was collected from the generated JMH jar:

```bash
JAR=benchmark/build/benchmarks/benchmark/jars/benchmark-benchmark-jmh-0.5.0-JMH.jar

java -jar "$JAR" 'io\.bluetape4k\.leader\.benchmark\.(BlockingLeaderContentionElectorBenchmark|SuspendLeaderContentionElectorBenchmark)\..*' \
  -p backend=lettuce,redisson,mongo,zookeeper \
  -p contenders=8 \
  -bm thrpt -tu s -f 0 -wi 0 -i 1 -r 100ms \
  -rf json -rff docs/benchmarks/2026-07-02-issue-521-contention-remote-throughput.json

java -jar "$JAR" 'io\.bluetape4k\.leader\.benchmark\.(BlockingLeaderContentionElectorBenchmark|SuspendLeaderContentionElectorBenchmark)\..*' \
  -p backend=lettuce,redisson,mongo,zookeeper \
  -p contenders=8 \
  -bm avgt -tu us -f 0 -wi 0 -i 1 -r 100ms \
  -rf json -rff docs/benchmarks/2026-07-02-issue-521-contention-remote-average-time.json

java -jar "$JAR" 'io\.bluetape4k\.leader\.benchmark\.Local.*LeaderContentionElectorBenchmark\..*' \
  -p contenders=8 \
  -bm thrpt -tu s -f 0 -wi 0 -i 1 -r 100ms \
  -rf json -rff docs/benchmarks/2026-07-02-issue-521-contention-local-throughput.json

java -jar "$JAR" 'io\.bluetape4k\.leader\.benchmark\.Local.*LeaderContentionElectorBenchmark\..*' \
  -p contenders=8 \
  -bm avgt -tu us -f 0 -wi 0 -i 1 -r 100ms \
  -rf json -rff docs/benchmarks/2026-07-02-issue-521-contention-local-average-time.json
```

Smoke verification also ran the local-only benchmark classes and a
container-backed Lettuce skip-path subset with `contenders=2`.

## Raw Data

- [`2026-07-02-issue-521-contention-remote-throughput.json`](2026-07-02-issue-521-contention-remote-throughput.json)
- [`2026-07-02-issue-521-contention-remote-average-time.json`](2026-07-02-issue-521-contention-remote-average-time.json)
- [`2026-07-02-issue-521-contention-local-throughput.json`](2026-07-02-issue-521-contention-local-throughput.json)
- [`2026-07-02-issue-521-contention-local-average-time.json`](2026-07-02-issue-521-contention-local-average-time.json)

## Result Table

| API | Scenario | Backend | Throughput (ops/s) | Average time (us/op) |
|---|---|---|---:|---:|
| Blocking | Skip held / wait 0 | local | 18,457,123 | 0.056 |
| Blocking | Skip held / wait 0 | lettuce | 2,307 | 511.16 |
| Blocking | Skip held / wait 0 | redisson | 2,993 | 357.93 |
| Blocking | Skip held / wait 0 | mongo | 1,210 | 844.57 |
| Blocking | Skip held / wait 0 | zookeeper | 323.93 | 2,296 |
| Blocking | Skip held / wait 25 ms | local | 38.47 | 26,109 |
| Blocking | Skip held / wait 25 ms | lettuce | 38.5 | 26,058 |
| Blocking | Skip held / wait 25 ms | redisson | 37.9 | 25,976 |
| Blocking | Skip held / wait 25 ms | mongo | 38.83 | 25,626 |
| Blocking | Skip held / wait 25 ms | zookeeper | 30.38 | 32,747 |
| Blocking | Parallel / wait 0 | lettuce | 362.43 | 2,932 |
| Blocking | Parallel / wait 0 | redisson | 350 | 2,846 |
| Blocking | Parallel / wait 0 | mongo | 158.84 | 6,458 |
| Blocking | Parallel / wait 0 | zookeeper | 41.87 | 17,101 |
| Blocking | Parallel / wait 25 ms | local | 37.08 | 27,066 |
| Blocking | Parallel / wait 25 ms | lettuce | 34.51 | 30,093 |
| Blocking | Parallel / wait 25 ms | redisson | 35.04 | 30,112 |
| Blocking | Parallel / wait 25 ms | mongo | 28.04 | 33,632 |
| Blocking | Parallel / wait 25 ms | zookeeper | 31.08 | 35,607 |
| Blocking | Mixed acquire + skip | local | 37.54 | 26,720 |
| Blocking | Mixed acquire + skip | lettuce | 33.44 | 34,213 |
| Blocking | Mixed acquire + skip | redisson | 34.35 | 31,610 |
| Blocking | Mixed acquire + skip | mongo | 32.81 | 33,646 |
| Blocking | Mixed acquire + skip | zookeeper | 24.67 | 36,621 |
| Suspend | Skip held / wait 0 | lettuce | 2,381 | 612.83 |
| Suspend | Skip held / wait 0 | redisson | 2,557 | 467.89 |
| Suspend | Skip held / wait 0 | mongo | 1,170 | 1,126 |
| Suspend | Skip held / wait 0 | zookeeper | 406.58 | 2,788 |
| Suspend | Skip held / wait 25 ms | local | 38.76 | 26,341 |
| Suspend | Skip held / wait 25 ms | lettuce | 38.45 | 25,710 |
| Suspend | Skip held / wait 25 ms | redisson | 22.11 | 30,111 |
| Suspend | Skip held / wait 25 ms | mongo | 37 | 27,206 |
| Suspend | Skip held / wait 25 ms | zookeeper | 34.69 | 33,887 |
| Suspend | Parallel / wait 25 ms | local | 35.57 | 27,552 |
| Suspend | Parallel / wait 25 ms | lettuce | 32.96 | 29,658 |
| Suspend | Parallel / wait 25 ms | redisson | 29.48 | 37,131 |
| Suspend | Parallel / wait 25 ms | mongo | 28.67 | 33,180 |
| Suspend | Parallel / wait 25 ms | zookeeper | 30.94 | 33,493 |
| Suspend | Mixed acquire + skip | local | 37.63 | 26,499 |
| Suspend | Mixed acquire + skip | lettuce | 32.43 | 30,257 |
| Suspend | Mixed acquire + skip | redisson | 22.62 | 44,968 |
| Suspend | Mixed acquire + skip | mongo | 28.02 | 35,939 |
| Suspend | Mixed acquire + skip | zookeeper | 22.29 | 46,421 |

## Interpretation

The existing-holder setup verifies that the action body is not executed on a
skip result. Immediate skip rows therefore measure the backend cost of
detecting a held lock and returning `LeaderRunResult.Skipped`, not a hidden
action path.

The local blocking `waitTime=0` skip row is intentionally much faster than the
remote rows because it is an in-process lock-state check. The remote immediate
skip rows still have to touch their backend. In this short run, Redisson and
Lettuce lead the remote immediate skip rows, MongoDB sits in the middle, and
ZooKeeper pays the highest coordination cost.

Positive-wait rows cluster around one operation per roughly 25 ms wait window.
That is expected: the configured wait policy dominates the measurement, so
backend differences are compressed. These rows are more useful as regression
smoke checks than as backend ranking rows.

Parallel `waitTime=0` exists only for the blocking remote/shared benchmark.
The local suspend implementation uses `withTimeoutOrNull(0)`, so an immediate
free acquisition can time out before meaningful contention is measured. The
suspend local baseline therefore keeps the positive-wait and mixed rows, while
remote suspend immediate skip remains covered by the held-lock scenario.

The mixed acquire/skip rows keep one leader path and several skipped contender
paths in the same measurement. Their latency is close to the positive-wait
shape because skipped contenders still wait before returning. These rows are
useful for detecting regressions where a backend accidentally executes the
action on a skipped contender or leaks held state across iterations.
