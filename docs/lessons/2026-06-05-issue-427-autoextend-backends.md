# Issue 427 AutoExtend Backend Benchmark

## Context

Issue #427 asked for benchmark coverage across README-supported single-leader
`autoExtend` backends after Redis coverage landed in #422.

## Decision or Finding

Add focused Local and MongoDB normal-vs-`autoExtend` rows that mirror the Redis
quick and renewal-window benchmark shape. Do not re-measure Redis in this issue.

## Outcome

The benchmark module now has blocking and suspend Local/MongoDB auto-extension
rows. Fresh raw JSON and the interpretation report are preserved in
`docs/benchmarks/2026-06-05-issue-427-autoextend-backends.md`.

No production optimization issue was opened because the MongoDB short-window
rows still have broad error bounds, while Local quick rows mainly expose
watchdog scheduler start/close overhead.

## Verification

- `./gradlew :benchmark:tasks --all --no-daemon`
- `./gradlew :benchmark:compileBenchmarkKotlin --no-daemon --no-configuration-cache`
- `./gradlew :benchmark:benchmarkBenchmarkJar --no-daemon --no-configuration-cache --rerun-tasks`
- Focused JMH throughput and average-time runs for `.*AutoExtendBackendLeaderElectorBenchmark.*`.

## Future Guidance

Keep Redis lease-extension results tied to #422. For future auto-extension
benchmark gaps, first confirm that README/public API documents the backend or
option combination as supported. Unsupported group-election auto-extension
needs API/support work before benchmark rows.
