---
manualId: "bluetape4k-leader-redis-redisson"
id: "bluetape4k-leader-redis-redisson"
title: "Redis Redisson backend"
locale: "en"
kind: "library"
gradlePath: ":bluetape4k-leader-redis-redisson"
sourceDir: "leader-redis-redisson"
releaseRef: "0.4.0"
artifact: io.github.bluetape4k.leader:bluetape4k-leader-redis-redisson
---

# Redis Redisson backend

> Library module

## Problem {#problem}

Implements single, group, and strategic election with Redisson locks/semaphores and blocking/coroutine APIs.

## When to use it {#when-to-use}

Choose it when Redisson is already the Redis client and its distributed-object API is preferred over direct Lettuce scripting.

## Coordinates {#coordinates}

Artifact: `io.github.bluetape4k.leader:bluetape4k-leader-redis-redisson`

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.leader:bluetape4k-leader-redis-redisson")
}
```

## Core concepts {#concepts}

Acquisition always passes explicit `leaseTime`, so Redisson's native watchdog is disabled. Core `LeaderLeaseAutoExtender` is the only automatic extender when enabled.

## Quick start {#quick-start}

```kotlin
val elector = RedissonLeaderElector(redissonClient)
elector.runIfLeader("webhook-poller") { pollWebhooks() }
```

## API by task {#api-by-task}

Use blocking/suspend single/group and strategic electors plus factories. Group election maps to semaphore-style permits.

## Recommended patterns {#patterns}

Keep one caller-owned client, stable lock names, explicit lease sizing, idempotent actions, and a deliberate choice about shared `autoExtend`.

## Integrations {#integrations}

Spring factories use `RedissonClient`. redisson-watchdog explains the distinction between native watchdog and the library's shared extender.

## Configuration {#configuration}

Configure wait/lease/minimum lease, auto-extension, group size, Redis topology, client timeout, retry, and codec independently.

## Failure modes {#failures}

Contention returns `null`. Interrupts, Redis/client failures, lost ownership, and extend/release errors propagate according to the backend classifier.

## Operations {#operations}

Monitor Redis latency, connection state, lock TTL, permit usage, extension failures, skip rate, and failover events. Do not assume the native watchdog renewed a lock.

## Testing {#testing}

Test two clients, explicit lease expiry, shared extender, owner-safe release, group permits, strategic registry, failover, and suspend cancellation.

## Workshops and learning path {#workshops}

Run redisson-watchdog before production adoption, then compare rate-limiter and strategic-election examples.

## Limitations {#limitations}

Explicit leases can expire during long pauses. Redisson ownership still does not fence writes to another store.

## Sources {#sources}

[Elector](../../../../leader-redis-redisson/src/main/kotlin/io/bluetape4k/leader/redisson/RedissonLeaderElector.kt) · [Shared extender delegate](../../../../leader-redis-redisson/src/main/kotlin/io/bluetape4k/leader/redisson/internal/RedissonLockExtendDelegate.kt) · [Stable guide](../../../../leader-redis-redisson/README.md)

