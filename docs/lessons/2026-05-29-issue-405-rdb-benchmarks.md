# Issue 405 RDB Benchmarks

## Context

The benchmark docs had H2 SQL/R2DBC rows but no distributed SQL backend rows.
That made the Exposed backend picture misleading because H2 measures local
shape overhead, not infrastructure-backed coordination.

## Decision

Add PostgreSQL and MySQL benchmark params for blocking Exposed JDBC and suspend
Exposed R2DBC. Treat H2 as a local shape check and rename public charts to
distributed backend.

## Outcome

The full default benchmark now includes PostgreSQL and MySQL rows in both
throughput and average-time runs. Raw JSON is committed for the 2026-05-29 run,
and README tables/charts use that data.

## Verification

- `./gradlew :benchmark:compileBenchmarkKotlin --no-daemon`
- `./gradlew :benchmark:benchmarkBenchmark :benchmark:benchmarkAverageTimeBenchmark --no-configuration-cache --rerun-tasks --no-daemon`
- `xmllint --noout docs/images/readme-charts/leader-benchmark-distributed-*.svg`
- PNG chart visual inspection

## Future Guidance

Keep local/H2 rows out of distributed backend charts. For JVM-local
coordination, prefer local lock primitives instead of H2 leader election.
