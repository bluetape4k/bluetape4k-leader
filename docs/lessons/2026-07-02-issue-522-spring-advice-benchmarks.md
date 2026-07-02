# Issue #522 Spring advice benchmark lesson

## Context

Issue #522 needed a benchmark that isolates `@LeaderElection` Spring AOP advice
overhead from backend lock I/O.

## Decision

Use local blocking and suspend electors, and invoke `LeaderElectionAspect`
directly with a small benchmark-only `ProceedingJoinPoint` fixture. This keeps
the benchmark focused on annotation metadata, SpEL, AspectJ dispatch, coroutine
continuation wiring, bean selection, and recorder iteration.

## Outcome

The benchmark now covers direct vs advice paths, static lock names vs SpEL lock
names, sync and suspend methods, and `instrumentation=none|noop` recorder
configuration. README charts and raw JSON document the short JMH snapshot.

## Verification

- `:benchmark:compileBenchmarkKotlin`
- `:benchmark:benchmarkBenchmarkJar`
- JMH throughput and average-time smoke runs for `SpringLeaderAdviceBenchmark`
- `xmllint --noout` for the new SVG charts

## Future note

Keep real Micrometer registry overhead in a separate benchmark so this fixture
continues to isolate AOP dispatch and expression-evaluation cost.
