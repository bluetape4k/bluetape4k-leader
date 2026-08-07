---
title: "Testing leader election"
description: "Test the contract at three levels: deterministic core behavior, real backend ownership, and multi-instance scenarios."
releaseRef: 0.5.0
releaseCommit: 721a9a3808f67489d2bdb8177734325981c24977
---

# Testing leader election

Test the contract at three levels: deterministic core behavior, real backend ownership, and multi-instance scenarios.

## Unit contract

Use local electors or a fake to prove the action runs only when elected, skipped is not an exception, an elected `null` is distinguishable with result APIs, and action errors propagate. Use deterministic lock names and bounded timeouts.

## Backend integration

Run the real client against Testcontainers or an emulator. Verify atomic contention, owner-conditional release, expiry/reacquire, minimum lease, state snapshots, group slot limits, and supported extension paths. Keep heavyweight backends sequential to avoid shared-resource noise.

## Scenario test

Start two application instances or two independently configured electors. Observe exactly one side effect, then kill the leader and verify takeover after the documented lease/session boundary. Also test cancellation and graceful shutdown. A passing happy path does not prove partition safety, so document what the fixture cannot simulate.

## Release sources

- [`leader-core/src/testFixtures/kotlin/io/bluetape4k/leader/contract/AbstractLeaderElectorLeaderIdContractTest.kt`](../../../../leader-core/src/testFixtures/kotlin/io/bluetape4k/leader/contract/AbstractLeaderElectorLeaderIdContractTest.kt)
- [`leader-redis-lettuce/src/test/kotlin/io/bluetape4k/leader/lettuce/LettuceSuspendLeaderElectorTest.kt`](../../../../leader-redis-lettuce/src/test/kotlin/io/bluetape4k/leader/lettuce/LettuceSuspendLeaderElectorTest.kt)
- [`examples/batch-scheduler/src/test/kotlin/io/bluetape4k/leader/examples/batch/BatchSchedulerTest.kt`](../../../../examples/batch-scheduler/src/test/kotlin/io/bluetape4k/leader/examples/batch/BatchSchedulerTest.kt)

## Continue learning

- [Bluetape4k Leader manual](../index.md)
- [Failure and cancellation](failure-and-cancellation.md)
- [Observability and operations](observability-and-operations.md)
