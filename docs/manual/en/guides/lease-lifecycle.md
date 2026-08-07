---
title: "Lease lifecycle"
description: "Set wait, maximum lease, minimum hold, and extension from measured work rather than defaults."
releaseRef: 0.5.0
releaseCommit: 721a9a3808f67489d2bdb8177734325981c24977
---

# Lease lifecycle

Set wait, maximum lease, minimum hold, and extension from measured work rather than defaults.

## Four time decisions

`waitTime` bounds acquisition waiting. `leaseTime` bounds ownership. `minLeaseTime` prevents rapid reacquisition after a fast action and must not exceed the lease. `autoExtend` periodically renews supported single-leader leases while the action runs. Defaults are 5 seconds, 60 seconds, zero, and false.

## Sizing

Measure p99 action duration and backend latency. A fixed lease should cover p99 plus GC, scheduling, and network jitter. Keep wait time short for scheduled jobs that should skip, longer only when queueing is intentional. Use a minimum lease when downstream systems need spacing, not as a substitute for rate limiting.

## Expiry and release

Normal completion releases ownership with an owner token. A crash relies on TTL or session expiry. If work can outlive the lease, use supported auto-extension or split work into bounded checkpoints. Even with extension, downstream writes should be idempotent because no lease can eliminate every partition or pause race.

## Release sources

- [`leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderElectionOptions.kt`](../../../../leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderElectionOptions.kt)
- [`leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderLeaseAutoExtender.kt`](../../../../leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderLeaseAutoExtender.kt)
- [`leader-core/src/main/kotlin/io/bluetape4k/leader/LockExtender.kt`](../../../../leader-core/src/main/kotlin/io/bluetape4k/leader/LockExtender.kt)

## Continue learning

- [Bluetape4k Leader manual](../index.md)
- [Lease extension](../core/lease-extension.md)
- [Failure and cancellation](failure-and-cancellation.md)
