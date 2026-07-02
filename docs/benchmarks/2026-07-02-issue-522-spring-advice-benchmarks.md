# Issue #522 Spring Leader Advice Benchmarks

Issue #522 measures the framework overhead added by `@LeaderElection` Spring
AOP advice compared with direct local elector calls. The fixture uses local
blocking and suspend electors so backend I/O does not hide annotation lookup,
argument inspection, SpEL evaluation, coroutine continuation handling, and
optional AOP recorder dispatch.

## Commands

```bash
./gradlew :benchmark:compileBenchmarkKotlin --no-daemon --no-configuration-cache --console=plain
./gradlew :benchmark:benchmarkBenchmarkJar --no-daemon --no-configuration-cache --console=plain

JAR=benchmark/build/benchmarks/benchmark/jars/benchmark-benchmark-jmh-0.5.0-JMH.jar

java -jar "$JAR" 'io\.bluetape4k\.leader\.benchmark\.SpringLeaderAdviceBenchmark\..*' \
  -p instrumentation=none,noop \
  -bm thrpt -tu s -f 1 -wi 1 -i 2 -r 500ms \
  -rf json -rff docs/benchmarks/2026-07-02-issue-522-spring-advice-throughput.json

java -jar "$JAR" 'io\.bluetape4k\.leader\.benchmark\.SpringLeaderAdviceBenchmark\..*' \
  -p instrumentation=none,noop \
  -bm avgt -tu us -f 1 -wi 1 -i 2 -r 500ms \
  -rf json -rff docs/benchmarks/2026-07-02-issue-522-spring-advice-average-time.json
```

## Charts

![Spring advice throughput](../images/readme-charts/leader-spring-advice-throughput-chart-01.png)

![Spring advice latency](../images/readme-charts/leader-spring-advice-latency-chart-01.png)

## Results

| Benchmark | Instrumentation | Throughput (ops/s) | Average time (us/op) |
|---|---:|---:|---:|
| direct sync | none | 2,255,925 | 0.44 |
| direct sync | noop | 2,258,324 | 0.44 |
| advice sync static | none | 1,718,542 | 0.56 |
| advice sync static | noop | 1,691,326 | 0.58 |
| advice sync SpEL | none | 1,034,113 | 0.94 |
| advice sync SpEL | noop | 1,030,415 | 0.99 |
| direct suspend | none | 28,987,468 | 0.035 |
| direct suspend | noop | 29,639,156 | 0.036 |
| advice suspend static | none | 544,163 | 1.70 |
| advice suspend static | noop | 592,620 | 1.66 |
| advice suspend SpEL | none | 449,808 | 2.21 |
| advice suspend SpEL | noop | 451,542 | 2.25 |

## Interpretation

The blocking static-name advice path adds a small amount of framework work over
the direct local elector baseline: annotation metadata lookup, AspectJ
join-point dispatch, bean selection, and recorder iteration. The SpEL rows are
slower because they evaluate the annotated expression against method arguments
on every invocation.

The suspend direct baseline is intentionally very small in this local fixture,
so the relative gap to Spring advice looks large. The absolute advice cost is
still in low microseconds: static suspend advice is about 1.7 us/op and SpEL
suspend advice is about 2.2 us/op in this short run. That is the useful number
for deciding whether annotation advice is acceptable around a real distributed
backend, where lock I/O usually dominates.

`instrumentation=noop` installs the no-op AOP metrics recorder. It does not
materially change the measured shape here; the small differences are within the
short JMH run's noise. A real Micrometer recorder is intentionally left to the
separate metrics-overhead issue so this fixture stays focused on advice
dispatch and expression evaluation.

Raw data:

- [`2026-07-02-issue-522-spring-advice-throughput.json`](2026-07-02-issue-522-spring-advice-throughput.json)
- [`2026-07-02-issue-522-spring-advice-average-time.json`](2026-07-02-issue-522-spring-advice-average-time.json)
