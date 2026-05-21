# Plan - Issue #327 Cross-Backend Leader Benchmarks

> Date: 2026-05-21
> Scope: cross-backend benchmark module and first baseline

## Tasks

1. Add a non-published `benchmark` module.
   - Register it in `settings.gradle.kts`.
   - Apply `kotlinx-benchmark` with a dedicated `benchmark` source set under `benchmark/src/benchmark`.
   - Follow the existing bluetape4k pattern used by `bluetape4k-projects`, `bluetape4k-exposed`, and `bluetape4k-image`: `JvmBenchmarkTarget.jmhVersion`, `benchmarkImplementation`, and JSON report profiles.
   - Depend on backend modules and their runtime infrastructure.
   - Add a root `isNonPublishedProject` predicate and use it for NMCP plugin application, publishing/signing, NMCP aggregation, and Kover aggregation.
   - Add a CI `benchmark` path filter and a compile-only `:benchmark:compileBenchmarkKotlin` job; do not run timing benchmark in CI.

2. Implement benchmark fixtures.
   - Start Testcontainers/embedded infrastructure in JMH trial setup.
   - Create reusable clients/electors once per trial.
   - Keep lock names fixed per benchmark method to avoid measuring unbounded key/table/path growth.
   - Use identical leader options where backend contracts allow it.
   - Use containerized Hazelcast, not embedded Hazelcast, so topology is comparable to other remote backends.
   - Verify bounded state after a smoke run or document retained state caveats in the baseline.

3. Implement benchmark methods.
   - Blocking benchmark class: local, Lettuce, Redisson, Exposed JDBC H2, MongoDB, Hazelcast, ZooKeeper.
   - Suspend benchmark class: local, Lettuce, Redisson, Exposed R2DBC H2, MongoDB, Hazelcast, ZooKeeper.
   - Treat throughput as the canonical comparison metric; average time remains auxiliary.
   - Consume action results with `Blackhole`.

4. Verify and baseline.
   - Run `:benchmark:compileBenchmarkKotlin`.
   - Run `:benchmark:benchmarkBenchmark :benchmark:benchmarkAverageTimeBenchmark`.
   - Record `docs/benchmarks/2026-05-21-leader-cross-backend-baseline.md`.
   - Reference `benchmark/build/reports/benchmarks/` as the machine-readable input for #328 charts.

5. Review and package.
   - Run targeted tests/compilation as needed.
   - Run Codex review against `origin/develop`.
   - Add `docs/lessons/2026-05-21-issue-327-leader-cross-backend-benchmarks.md`.
   - Commit, push, create PR, and use rebase merge only after checks pass.

## Deferred

- PostgreSQL/MySQL JDBC and R2DBC benchmark rows.
- Multi-threaded contention benchmark rows.
- README charts and comparison narrative (#328).
- Optimization experiments (#329).
