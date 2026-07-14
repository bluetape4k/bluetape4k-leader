---
title: "Micrometer integration"
description: "Measure election decisions and AOP execution while controlling high-cardinality lock names."
releaseRef: 0.4.0
releaseCommit: 17ab7f872c1f96318c73d3580729cac20a67e017
---

# Micrometer integration

Measure election decisions and AOP execution while controlling high-cardinality lock names.

## Two integration points

`MicrometerLeaderElectionListener` records lifecycle decisions from elector events. `MicrometerLeaderAopMetricsRecorder` instruments annotation-driven execution. Choose the one matching the invocation boundary; avoid double-counting the same call through both without an explicit dashboard model.

## Tags

Use bounded tags for backend, result, and stable job family. Normalize or reject tenant-rich lock names before turning them into labels. Store exact lock names in structured logs when needed for diagnosis.

## Dashboard

Pair rates with duration and backend health. An elected counter alone cannot reveal a stuck action; skipped alone cannot distinguish healthy contention from excessive overlap. The Prometheus example includes an end-to-end scrape and dashboard shape that should be adapted to local naming and alert thresholds.

## Release sources

- [`leader-micrometer/src/main/kotlin/io/bluetape4k/leader/micrometer/MicrometerLeaderElectionListener.kt`](../../../../leader-micrometer/src/main/kotlin/io/bluetape4k/leader/micrometer/MicrometerLeaderElectionListener.kt)
- [`leader-micrometer/src/main/kotlin/io/bluetape4k/leader/micrometer/MicrometerLeaderAopMetricsRecorder.kt`](../../../../leader-micrometer/src/main/kotlin/io/bluetape4k/leader/micrometer/MicrometerLeaderAopMetricsRecorder.kt)
- [`examples/prometheus-dashboard/README.md`](../../../../examples/prometheus-dashboard/README.md)

## Continue learning

- [Bluetape4k Leader manual](../index.md)
- [Observability and operations](../guides/observability-and-operations.md)
- [Spring Boot integration](spring-boot.md)
