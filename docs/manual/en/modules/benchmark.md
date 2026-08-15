---
manualId: "benchmark"
id: "benchmark"
title: "Leader election benchmarks"
locale: "en"
kind: "benchmark"
gradlePath: ":benchmark"
sourceDir: "benchmark"
releaseRef: "0.5.0"
artifact: null
---

# Leader election benchmarks

> Performance benchmark

## Problem {#problem}

This non-published module compares election backends with one `kotlinx-benchmark`/JMH harness. The 0.5.0 manual anchors its comparison to the 2026-05-29 evidence: throughput is better when higher and average time is better when lower, but only rows from the same workload and runtime target are comparable.

## When to use it {#when-to-use}

Use the suite to compare a candidate with a baseline on the same machine or to detect a large regression. Do not treat the tables as a production capacity promise or rank unrelated infrastructure from a short microbenchmark.

## Coordinates {#coordinates}

This module is not published. Check out tag `0.5.0` and run `:benchmark` from the repository.

## Core concepts {#concepts}

The harness separates blocking and suspend APIs, local and distributed backends, throughput and average-time modes. Kubernetes runs in a separate target because its Fabric8/Vert.x runtime differs from the default etcd target. Forks, threads, warmup, measurement windows, containers, JDK, OS, and hardware are part of every result.

## Quick start {#quick-start}

```bash
./gradlew :benchmark:benchmarkBenchmark \
  :benchmark:benchmarkAverageTimeBenchmark \
  --no-configuration-cache --rerun-tasks

./gradlew :benchmark:kubernetesBenchmarkBenchmark \
  :benchmark:kubernetesBenchmarkAverageTimeBenchmark \
  --no-configuration-cache --rerun-tasks
```

## API by task {#api-by-task}

`BackendLeaderElectorBenchmark` covers blocking paths and `SuspendBackendLeaderElectorBenchmark` covers coroutine paths. Use benchmark filters for a focused experiment and preserve raw JSON instead of copying console summaries.

## Recommended patterns {#patterns}

Change one variable, keep harness and environment fixed, run baseline and candidate in the same session, and compare confidence intervals. Report metric direction and error bounds. Re-run noisy rows instead of explaining them away.

## Integrations {#integrations}

The harness starts backend infrastructure. The 2026-05-29 SQL rows use Exposed JDBC for blocking and Exposed R2DBC for suspend. Kubernetes remains isolated in its own runtime target.

## Configuration {#configuration}

A reproduction record must include JDK, OS, CPU, container versions, fork/thread count, warmup, measurement duration/count, Gradle command, and selected filters. Without that context, numbers from another machine are not comparable.

## Failure modes {#failures}

Failed containers, a saturated laptop, thermal throttling, or a mixed runtime classpath invalidate the run. Very wide error bounds cannot support a tuning conclusion. One faster sample is not evidence of a fix.

## Operations {#operations}

Keep throughput and average-time raw JSON together with a short interpretation. If the metrics disagree, inspect setup overhead and outliers before making a claim. Archive the exact commit and environment with the result.

## Testing {#testing}

Compile benchmark source sets before a long run, smoke a narrow filter, then execute full throughput and average-time modes sequentially. Confirm that the harness uses stable 0.5.0 APIs and no later source surface.

## Workshops and learning path {#workshops}

Read the cross-backend baseline first, then inspect the benchmark class for the measured operation. Use each backend manual to interpret operational trade-offs; the benchmark does not choose a backend for the application.

## Limitations {#limitations}

The 2026-05-29 evidence represents one machine, short JMH windows, and containerized dependencies. It does not model network partitions, production data size, multi-node contention, failover, or protected business-action cost.

## Sources {#sources}

[Benchmark guide](../../../../benchmark/README.md) · [2026-05-29 throughput JSON](../../../../docs/benchmarks/2026-05-29-issue-405-rdb-backend-throughput.json) · [2026-05-29 average-time JSON](../../../../docs/benchmarks/2026-05-29-issue-405-rdb-backend-average-time.json) · [Harness](../../../../benchmark/src/benchmark/kotlin/io/bluetape4k/leader/benchmark/BackendLeaderElectorBenchmark.kt)
