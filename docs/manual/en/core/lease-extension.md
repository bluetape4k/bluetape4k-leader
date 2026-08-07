---
title: "Lease extension"
description: "Renew only while ownership still matches, and treat extension outcomes as operational decisions."
releaseRef: 0.5.0
releaseCommit: 721a9a3808f67489d2bdb8177734325981c24977
---

# Lease extension

Renew only while ownership still matches, and treat extension outcomes as operational decisions.

## Automatic and explicit

`autoExtend=true` starts the shared watchdog for supported single-leader backends. Code inside an elected scope can also call `LockExtender.extendActiveLockDetailed()` or the suspend variant. Group auto-extension is not a general 0.5.0 contract.

## Outcome handling

`Extended` carries a best-effort observed expiry. `NotHeld` means token mismatch, expiry, or takeover. `WrongThread` is specific to a Redisson thread-bound lock used from another thread. `BackendError` preserves transient versus non-transient classification. The Boolean shortcut hides detail and should not drive an operator runbook.

## Safety rule

The backend must extend atomically under the current owner condition. A user extension records its later deadline so the watchdog does not shorten it on the next tick. Stop protected writes when ownership is lost; renewing after expiry cannot retroactively make earlier work safe.

## Release sources

- [`leader-core/src/main/kotlin/io/bluetape4k/leader/LockExtender.kt`](../../../../leader-core/src/main/kotlin/io/bluetape4k/leader/LockExtender.kt)
- [`leader-core/src/main/kotlin/io/bluetape4k/leader/ExtendOutcome.kt`](../../../../leader-core/src/main/kotlin/io/bluetape4k/leader/ExtendOutcome.kt)
- [`leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderLeaseAutoExtender.kt`](../../../../leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderLeaseAutoExtender.kt)

## Continue learning

- [Bluetape4k Leader manual](../index.md)
- [Lease lifecycle](../guides/lease-lifecycle.md)
- [Failure and cancellation](../guides/failure-and-cancellation.md)
