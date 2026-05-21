# Issue #326 — leader-core JMH benchmarks design

## Context

`bluetape4k-leader` currently has no benchmark source set or JMH Gradle setup.
Issue #50 previously set a hot-path target for the history recorder path:
in-memory history recording should add no more than 1 ms p99 overhead to
`runIfLeader`. That requirement has no executable measurement today.

This issue is the first benchmark PR under epic #325. It creates a reusable
JMH convention and `leader-core` microbenchmarks so later backend comparison and
self-improve work can use evidence instead of intuition.

## Scope

- Add a repo-local JMH convention plugin in `buildSrc`.
- Apply it only to `leader-core`.
- Add microbenchmarks for local leader election execution models:
  blocking, `CompletableFuture` async, virtual-thread, and coroutine suspend.
- Add microbenchmarks for `SafeLeaderHistoryRecorder` and
  `SuspendSafeLeaderHistoryRecorder` with no-op and in-memory sinks.
- Produce a short baseline report under `docs/benchmarks/`.

## Non-goals

- Cross-backend Testcontainers benchmarks for Lettuce, Redisson, Exposed,
  MongoDB, Hazelcast, or ZooKeeper. That is issue #327.
- README benchmark charts. That is issue #328 after comparable backend data is
  available.
- Runtime optimization or self-improve changes. That is issue #329 after the
  baseline exists.
- Strategic decorator paths such as `LocalStrategicLeaderElector` and
  `LocalStrategicSuspendLeaderElector`.

## Design

### Gradle convention

Use `me.champeau.jmh` version `0.7.3`, the latest Gradle Plugin Portal version
created on 2025-01-30. The plugin documents `src/jmh` as the benchmark source
location and adds a `jmh` task that writes reports under `build/reports/jmh`.
`buildSrc` must include `gradlePluginPortal()` so the plugin marker dependency
can be resolved.

The convention plugin lives in `buildSrc` with id
`bluetape4k.jmh-conventions`. It applies the upstream plugin and sets a short
developer-friendly default profile:

- `jmhVersion = "1.37"`
- `benchmarkMode = ["thrpt", "avgt"]`
- `timeUnit = "us"`
- `warmupIterations = 2`
- `iterations = 3`
- `fork = 1`
- `threads = 1`
- `resultFormat = "JSON"`
- reports under `build/reports/jmh`

The benchmark classes can still override fine details with JMH annotations.

### Benchmarks

`LocalLeaderElectorBenchmark` uses `@State(Scope.Benchmark)` and measures local
uncontended execution overhead:

- blocking `LocalLeaderElector.runIfLeader`
- async `LocalLeaderElector.runAsyncIfLeader`
- async-only `LocalAsyncLeaderElector.runAsyncIfLeader`
- virtual-thread `LocalVirtualThreadLeaderElector.runAsyncIfLeader`
- coroutine `LocalSuspendLeaderElector.runIfLeader`

The suspend benchmark must wrap calls in `runBlocking {}` because JMH cannot
invoke a suspend benchmark method directly. The reported number therefore
includes the runBlocking bridge cost. The virtual-thread benchmark includes
virtual-thread submission and scheduling cost, not just lock acquisition cost.

`HistoryRecorderBenchmark` measures recorder wrapper overhead without backend
I/O:

- blocking no-op sink acquire + complete
- blocking in-memory sink acquire + complete
- suspend no-op sink acquire + complete
- suspend in-memory sink acquire + complete

The in-memory sinks store records in concurrent maps and model the minimum
non-network persistence cost. They are benchmark fixtures, not production API.

## Acceptance Criteria

- `./gradlew :bluetape4k-leader-core:compileJmhKotlin` succeeds.
- `./gradlew :bluetape4k-leader-core:jmh` succeeds for the new benchmarks.
- JMH JSON and human output are generated under
  `leader-core/build/reports/jmh/`.
- `docs/benchmarks/2026-05-21-leader-core-baseline.md` records the local run
  command, environment, scenario list, and key results.
- A lesson is added under `docs/lessons/`.

## Risks

- JMH results on a developer laptop are not release-grade performance claims.
  The baseline report must mark them as local, comparative, and reproducibility
  oriented.
- Async and virtual-thread paths include scheduling cost. They must not be
  directly compared as pure lock acquisition cost without that caveat.
- If the Gradle JMH plugin is incompatible with the current Gradle/Kotlin stack,
  fall back to a custom `src/jmh` source set and `JavaExec` JMH task in the same
  PR.
- The JMH Gradle plugin uses a bytecode generator task rather than a Kotlin
  kapt path; `compileJmhKotlin` and `jmhRunBytecodeGenerator` must both be
  verified before claiming Kotlin benchmark support.

## References

- Gradle Plugin Portal: `me.champeau.jmh` 0.7.3 is latest and documents the
  build-logic dependency coordinate.
- JMH Gradle Plugin README: `src/jmh`, `jmh` task, result paths, and extension
  options.
- `docs/superpowers/specs/2026-05-12-issue-50-leader-history-audit-contract-design.md`
- `docs/superpowers/plans/2026-05-12-issue-50-leader-history-audit-contract-plan.md`
