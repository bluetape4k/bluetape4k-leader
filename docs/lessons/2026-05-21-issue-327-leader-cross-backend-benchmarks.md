# Issue #327 leader cross-backend benchmarks

## Context

Leader self-improve work needed comparable baseline data across local, Redis,
SQL, MongoDB, Hazelcast, and ZooKeeper implementations. The workspace standard
for benchmarks is `kotlinx-benchmark`, with JMH as the JVM backend.

## Decision

Created a non-published central `benchmark/` module with
`src/benchmark/kotlin`, `benchmarkImplementation`, explicit
`JvmBenchmarkTarget.jmhVersion`, INFO logging, and two JSON profiles:
throughput and average time. The benchmark classpath is intentionally explicit
instead of inheriting all test or compile-only dependencies. The prior #326
local benchmark classes were moved from `leader-core/src/jmh` into this module.

## Outcome

`./gradlew :benchmark:benchmarkBenchmark :benchmark:benchmarkAverageTimeBenchmark --no-configuration-cache --rerun-tasks`
completed successfully in 5m 18s with fork 1 after setup smoke checks were
added. Results are recorded in
`docs/benchmarks/2026-05-21-leader-cross-backend-baseline.md`.

## Verification

- `./gradlew :benchmark:compileBenchmarkKotlin --no-configuration-cache`
- `./gradlew :benchmark:benchmarkBenchmark :benchmark:benchmarkAverageTimeBenchmark --no-configuration-cache --rerun-tasks`
- `actionlint .github/workflows/ci.yml`
- `git diff --check`

## Future Guidance

- For bluetape4k benchmark work, start from the established
  `kotlinx-benchmark` source-set pattern instead of adding a direct JMH Gradle
  plugin.
- Keep throughput as the primary ranking metric and average time as auxiliary
  latency evidence.
- Fail setup early when infrastructure is not connected, and keep a setup smoke
  check so benchmarks do not silently measure a skipped leader path.
- Do not compare H2 rows as distributed backend claims; they measure local SQL
  layer overhead.
- Repeat MongoDB measurements or lengthen the profile before using MongoDB rows
  for optimization decisions.
