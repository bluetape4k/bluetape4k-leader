---
title: "Interpret benchmark results"
description: "Use the 0.5.0 JMH data for controlled comparisons, not infrastructure purchasing decisions."
releaseRef: 0.5.0
releaseCommit: 721a9a3808f67489d2bdb8177734325981c24977
---

# Interpret benchmark results

Use the 0.5.0 JMH data for controlled comparisons, not infrastructure purchasing decisions.

## What was measured

The benchmark module uses kotlinx-benchmark with JMH. The recorded cross-backend runs use one fork, one thread, two warmups, and three one-second measurements on one machine. PostgreSQL, MySQL, Kubernetes, Redis extension, history recorder, and selected repeat runs have separate raw artifacts and constraints.

## Read direction and uncertainty

Higher throughput and lower average time are better only within comparable rows. Error bounds are broad for several container-backed results; overlapping intervals do not justify a winner. Local and H2 rows measure in-process or local SQL overhead and are intentionally separated from distributed backend charts.

## Use in engineering

Repeat the exact command before and after a code change on the same machine. Preserve environment, raw JSON, warmup, fork, and error data. For backend selection, benchmark the deployed topology and actual action; the release results cannot model your network, durability, or pool contention.

## Release sources

- [`benchmark/README.md`](../../../../benchmark/README.md)
- [`docs/benchmarks/2026-05-21-leader-cross-backend-baseline.md`](../../../benchmarks/2026-05-21-leader-cross-backend-baseline.md)
- [`docs/benchmarks/2026-05-29-issue-405-rdb-backend-throughput.json`](../../../benchmarks/2026-05-29-issue-405-rdb-backend-throughput.json)

## Continue learning

- [Bluetape4k Leader manual](../index.md)
- [Choose a backend](../guides/backend-selection.md)
- [Testing leader election](../guides/testing.md)
