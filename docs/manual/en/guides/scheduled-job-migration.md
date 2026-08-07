---
title: "Migrate a scheduled job"
description: "Add election around an idempotent job without mistaking it for durable scheduling."
releaseRef: 0.5.0
releaseCommit: 721a9a3808f67489d2bdb8177734325981c24977
---

# Migrate a scheduled job

Add election around an idempotent job without mistaking it for durable scheduling.

## Before wrapping

Give the job a stable logical name, make its effects idempotent, record durable progress, and decide whether a missed run should be skipped, retried, or backfilled. Leader election only coordinates concurrent attempts; it does not persist a schedule.

## Wrap and size

Place only the mutually exclusive section inside `runIfLeader`. Keep unrelated preparation outside when safe. Derive lease time from measured execution and add an idempotency key to external writes. If one run can exceed the lease, enable a supported extension path or split the work.

## Rollout

Deploy first with metrics and no destructive cleanup. Confirm one elected event per schedule, expected skips on other nodes, bounded duration, and clean release. Rehearse leader termination. For Quartz, Spring Batch, or another durable scheduler, keep its recovery semantics and use election only where cross-instance exclusion is still needed.

## Release sources

- [`examples/batch-scheduler/README.md`](../../../../examples/batch-scheduler/README.md)
- [`examples/migration-gate/README.md`](../../../../examples/migration-gate/README.md)
- [`examples/ktor-app/README.md`](../../../../examples/ktor-app/README.md)

## Continue learning

- [Bluetape4k Leader manual](../index.md)
- [Lease lifecycle](lease-lifecycle.md)
- [Spring Boot or Ktor](spring-vs-ktor.md)
- [Testing leader election](testing.md)
