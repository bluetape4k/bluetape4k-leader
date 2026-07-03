# Issue #574 Redis Strategic Candidate Lookup Benchmarks

Issue #574 bounds the Redis strategic elector candidate lookup hot path and
adds benchmark coverage for Redis-backed strategic candidate listing and scored
election execution.

## Scope

- Backends: Lettuce and Redisson.
- Candidate count in this smoke snapshot: 16.
- Benchmarks:
  - `listCandidates`: registry candidate lookup only.
  - `runScoredElection`: candidate lookup plus scored strategy election.

## Commands

```bash
./gradlew :benchmark:compileBenchmarkKotlin :benchmark:benchmarkBenchmarkJar --no-daemon --no-configuration-cache --console=plain

java -jar benchmark/build/benchmarks/benchmark/jars/benchmark-benchmark-jmh-0.5.0-JMH.jar \
  '.*RedisStrategicCandidateLookupBenchmark.*' \
  -p candidateCount=16 -bm avgt -tu us -f 1 -wi 0 -i 1 -r 200ms -w 100ms \
  -rf json -rff docs/benchmarks/2026-07-04-issue-574-redis-strategic-lookup-smoke.json
```

Run shape: one fork, one thread, no warmup iteration, one 200 ms measurement
iteration. Use this as a local smoke point that proves the benchmark wiring and
captures the current hot path shape, not as release-grade performance evidence.

Raw data:

- [`2026-07-04-issue-574-redis-strategic-lookup-smoke.json`](./2026-07-04-issue-574-redis-strategic-lookup-smoke.json)

## Results

Lower is better for average time.

| Benchmark | Backend | Candidates | Average time (us/op) |
|---|---|---:|---:|
| `listCandidates` | Lettuce | 16 | 2,530.172 |
| `listCandidates` | Redisson | 16 | 1,518.235 |
| `runScoredElection` | Lettuce | 16 | 3,094.977 |
| `runScoredElection` | Redisson | 16 | 1,478.360 |

## Interpretation

The Lettuce candidate registry no longer performs a Redis keyspace scan for
strategic candidate listing. It stores node ids in a lock-scoped Redis set and
fetches candidate payloads by exact keys. This keeps lookup bounded by the
candidate index for the lock instead of by unrelated Redis keyspace size.

Redisson already used a lock-scoped map and remains the reference shape for a
backend-owned candidate index. The benchmark keeps Lettuce and Redisson in the
same class so future changes can compare lookup and election rows with the same
JMH parameters.
