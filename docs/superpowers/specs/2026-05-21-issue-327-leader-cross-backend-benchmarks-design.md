# Spec - Issue #327 Cross-Backend Leader Benchmarks

> Date: 2026-05-21
> Scope: non-published `benchmark` module, `kotlinx-benchmark` cross-backend leader elector baselines
> Parent: #325
> Depends on: #326 local benchmark scenarios; this issue centralizes the harness

## Problem

`leader-core` has local benchmark scenarios, but distributed backend costs are still not comparable. Self-improve work would optimize without knowing whether the bottleneck is API overhead, Redis round trips, database row locks, MongoDB upserts, Hazelcast map operations, or ZooKeeper Curator mutexes.

## Goal

Add an explicit cross-backend benchmark surface that can run the same uncontended `runIfLeader` action across supported backend families and record a baseline before optimization work starts.

## Non-Goals

- No production API changes.
- No README chart publication in this issue; #328 owns README/chart integration.
- No optimization in this issue; #329 owns benchmark-driven self-improve.
- No CI-enforced benchmark timing gate.

## Benchmark Scope

The initial comparable matrix should include:

- Local blocking and suspend baselines for context.
- Redis Lettuce blocking and suspend electors.
- Redis Redisson blocking and suspend electors.
- Exposed JDBC H2 blocking elector.
- Exposed R2DBC H2 suspend elector.
- MongoDB blocking and suspend electors.
- Hazelcast blocking and suspend electors using a containerized Hazelcast member plus client, matching the network-hop shape of Redis, MongoDB, and ZooKeeper.
- ZooKeeper blocking and suspend electors.

PostgreSQL/MySQL JDBC and R2DBC are intentionally deferred unless the first module shape proves stable, because they multiply container startup and cleanup cost. H2 rows are labeled as database-backend shape checks, not distributed database claims.

Throughput is the canonical ranking metric for the baseline. Average-time rows from the secondary `kotlinx-benchmark` profile remain auxiliary evidence and are recorded only to help explain latency shape.

## Acceptance Criteria

- A non-published benchmark module exists and is excluded from Maven Central aggregation, signing, and Kover aggregation by the same root predicate at all relevant build filter sites.
- CI has a compile-only benchmark job that runs `:benchmark:compileBenchmarkKotlin` when benchmark files or shared benchmark infrastructure change; CI does not execute timing benchmark.
- `./gradlew :benchmark:compileBenchmarkKotlin --no-configuration-cache` compiles.
- `./gradlew :benchmark:benchmarkBenchmark :benchmark:benchmarkAverageTimeBenchmark --no-configuration-cache` runs at least the initial benchmark matrix locally.
- Benchmark setup starts containers outside measurement and measures only steady-state uncontended acquire/action/release calls.
- Benchmark setup keeps backend state bounded: fixed lock names are reused, unlocked state is released by each elector, and baseline notes explicitly call out any backend that retains rows/documents/paths between iterations.
- Baseline results are recorded under `docs/benchmarks/`.
- Baseline results identify the source artifact as `benchmark/build/reports/benchmarks/` so #328 can generate charts from a stable schema.
- A lesson entry records caveats and future comparison rules.

## Caveats

- Container-backed backend results are local-machine evidence, not release-grade performance claims.
- Single-threaded uncontended results do not model lock contention, network jitter, replica failover, or multi-node split-brain behavior.
- Suspend results include `runBlocking` bridge cost because JMH invokes non-suspend benchmark methods.
- ZooKeeper suspend includes per-call single-thread ownership machinery by implementation design.
