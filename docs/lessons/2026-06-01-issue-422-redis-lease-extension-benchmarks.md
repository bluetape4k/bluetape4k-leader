# Issue #422 Redis lease extension benchmarks

## Context

Milestone 0.3.0 needed focused Redis benchmark rows for Lettuce and Redisson
lease extension behavior. The issue mentioned Redisson watchdog behavior, but
the current Redisson electors always pass an explicit `leaseTime`.

## Decision

Benchmark the public behavior that exists today: normal execution versus the
shared bluetape4k `autoExtend` path. Do not add production watchdog behavior or
optimization without separate design and evidence.

## Outcome

Added blocking and suspend Redis lease-extension benchmark classes, stored raw
throughput and average-time JSON artifacts, and documented the Redisson watchdog
caveat in the benchmark README/report.

## Verification

- `./gradlew :benchmark:compileBenchmarkKotlin --no-daemon`
- `./gradlew :benchmark:benchmarkBenchmark :benchmark:benchmarkAverageTimeBenchmark --no-daemon --no-configuration-cache --rerun-tasks`

## Future Rule

If native Redisson watchdog rows are requested again, first expose or design the
watchdog acquisition path explicitly. Do not label shared `autoExtend` rows as
native Redisson watchdog evidence.
