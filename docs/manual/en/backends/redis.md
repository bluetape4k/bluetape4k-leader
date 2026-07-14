---
title: "Redis backends"
description: "Choose Lettuce for command-level control or Redisson for its client ecosystem while respecting thread-bound ownership."
releaseRef: 0.4.0
releaseCommit: 17ab7f872c1f96318c73d3580729cac20a67e017
---

# Redis backends

Choose Lettuce for command-level control or Redisson for its client ecosystem while respecting thread-bound ownership.

## Common contract

Both modules provide single, group, suspend, factory, state, and strategic variants. They use owner tokens for conditional release and the shared lease auto-extender. Redis server availability and key expiry are part of the correctness boundary.

## Lettuce

Lettuce exposes sync and coroutine-friendly command paths and is a good fit when the application already owns a Lettuce connection. Validate connection lifecycle and command timeout separately from election `waitTime`.

## Redisson

Redisson integrates with `RLock`/semaphore-style facilities. Some ownership is tied to the acquiring thread; extending from another thread yields `WrongThread`. The 0.4.0 elector passes an explicit lease, so benchmarked `autoExtend` refers to bluetape4k's shared extender, not Redisson's native watchdog mode.

## Release sources

- [`leader-redis-lettuce/README.md`](../../../../leader-redis-lettuce/README.md)
- [`leader-redis-redisson/README.md`](../../../../leader-redis-redisson/README.md)
- [`docs/benchmarks/2026-06-01-issue-422-redis-lease-extension-throughput.json`](../../../benchmarks/2026-06-01-issue-422-redis-lease-extension-throughput.json)

## Continue learning

- [Bluetape4k Leader manual](../index.md)
- [Choose a backend](../guides/backend-selection.md)
- [Lease extension](../core/lease-extension.md)
