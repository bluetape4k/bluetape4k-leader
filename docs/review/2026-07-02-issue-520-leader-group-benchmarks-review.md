# Issue 520 Implementation Review

## Scope

Reviewed the staged issue #520 changes:

- `LeaderGroupElectorBenchmark` and `SuspendLeaderGroupElectorBenchmark`
- README and README.ko benchmark updates
- raw JMH JSON, benchmark report, chart SVG/PNG assets, and lesson note

## Findings

- P0: none.
- P1: none.

## Notes

- The initial suspend group benchmark included `exposed-r2dbc-h2`, but the
  generated JMH jar currently exposes a malformed
  `META-INF/services/io.r2dbc.spi.ConnectionFactoryProvider` resource, causing
  R2DBC H2 setup to produce zero JMH rows. The issue #520 benchmark now excludes
  the failing suspend H2 parameter and documents only local, Redis, MongoDB, and
  ZooKeeper for suspend group rows. Blocking JDBC H2 remains covered.
- `MultithreadingTester` is not applicable here because the slot holders are a
  benchmark fixture, not a concurrency correctness test. The benchmark uses real
  elector APIs and validates setup with smoke `runIfLeaderResult` checks.

## Verification Evidence

- `./gradlew :benchmark:compileBenchmarkKotlin --no-configuration-cache --console=plain --warning-mode all`: PASS
- `./gradlew :benchmark:benchmarkBenchmarkJar --no-daemon --no-configuration-cache --console=plain --rerun-tasks`: PASS
- Forked local JMH baseline for both group benchmark classes: 10 rows
- Latest chart-scope throughput snapshot check: 33 rows
- Latest chart-scope average-time snapshot check: 33 rows
- `xmllint --noout` on new chart SVG files: PASS
- `jq empty` on raw JSON files: PASS
- `git diff --cached --check`: PASS
