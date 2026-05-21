# Issue #326 — leader-core JMH benchmarks plan

## Goal

Create the first executable benchmark baseline for `leader-core`, then use that
baseline as the input for cross-backend comparison and later self-improve work.

## Tasks

1. Add JMH convention plugin.
   - Update `buildSrc/build.gradle.kts` with the plugin marker dependency.
   - Add `gradlePluginPortal()` to `buildSrc` repositories.
   - Register `bluetape4k.jmh-conventions`.
   - Implement `JmhConventionPlugin`.

2. Wire `leader-core`.
   - Apply `bluetape4k.jmh-conventions`.
   - Add `jmh` dependencies needed by benchmark sources.
   - Keep benchmark sources out of publication and normal tests.

3. Add local elector benchmarks.
   - Implement `LocalLeaderElectorBenchmark`.
   - Use uncontended locks and `@State(Scope.Benchmark)` state objects.
   - Include blocking, async, async-only, virtual-thread, and suspend paths.
   - Wrap suspend calls in `runBlocking`; report that bridge cost explicitly.

4. Add history recorder benchmarks.
   - Implement `HistoryRecorderBenchmark`.
   - Add benchmark-local in-memory blocking and suspend sinks.
   - Measure acquire + complete as the hot path.

5. Verify.
   - Run `:bluetape4k-leader-core:compileJmhKotlin`.
   - Run `:bluetape4k-leader-core:jmhRunBytecodeGenerator`.
   - Run targeted `:bluetape4k-leader-core:jmh` for the new benchmark package.
   - Run `:bluetape4k-leader-core:test` if benchmark wiring affects main/test
     classpaths.

## Fallback

If `me.champeau.jmh` 0.7.3 fails on the current Gradle/Kotlin stack, replace the
convention plugin with a manual `jmh` source set plus a `JavaExec` task using
`org.openjdk.jmh:jmh-core:1.37` and `org.openjdk.jmh:jmh-generator-bytecode:1.37`.

6. Document.
   - Add `docs/benchmarks/2026-05-21-leader-core-baseline.md`.
   - Add `docs/lessons/2026-05-21-issue-326-leader-core-benchmarks.md`.
   - Link issue #326 in the PR body.

## Validation Evidence To Capture

- Gradle compile command and result.
- JMH command and report path.
- Top-level throughput and average-time rows for each benchmark method.
- Any caveat that prevents treating the run as a release-grade benchmark.

## Stop Condition

Stop this PR when `leader-core` benchmark setup and baseline report are merged
as an independent artifact. Start issue #327 only after this PR has a clean
benchmark harness.
