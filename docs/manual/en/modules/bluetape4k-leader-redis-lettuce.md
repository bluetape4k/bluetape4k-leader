---
manualId: "bluetape4k-leader-redis-lettuce"
id: "bluetape4k-leader-redis-lettuce"
title: "Redis Lettuce backend"
locale: "en"
kind: "library"
gradlePath: ":bluetape4k-leader-redis-lettuce"
sourceDir: "leader-redis-lettuce"
releaseRef: "0.4.0"
artifact: io.github.bluetape4k.leader:bluetape4k-leader-redis-lettuce
---

# Redis Lettuce backend

> Library module

## Problem {#problem}

Implements single, fixed-slot group, and strategic election with Lettuce connections, token-owned Redis keys, Lua compare-and-delete/extend, and blocking/coroutine APIs.

## When to use it {#when-to-use}

Choose it when Redis is already operated and the application prefers Lettuce's connection model and explicit scripting.

## Coordinates {#coordinates}

Artifact: `io.github.bluetape4k.leader:bluetape4k-leader-redis-lettuce`

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.leader:bluetape4k-leader-redis-lettuce")
}
```

## Core concepts {#concepts}

A unique token owns a TTL key. Lua scripts make release and extension conditional. Strategic election stores candidate metadata and applies Core strategies.

## Quick start {#quick-start}

```kotlin
val elector = LettuceLeaderElector(connection)
elector.runIfLeader("daily-report") { generateReport() }
```

## API by task {#api-by-task}

Use the connection extension/factory, blocking/suspend single/group electors, and strategic electors. The connection is caller-owned.

## Recommended patterns {#patterns}

Use a dedicated key prefix, keep Redis client and connection lifecycle explicit, make actions idempotent, and plan behavior during failover.

## Integrations {#integrations}

Spring can build factories from Lettuce resources. strategic-election and rate-limiter show candidate and group patterns.

## Configuration {#configuration}

Configure wait/lease/minimum lease, auto-extension, key prefix, group size, retry, client timeouts, topology refresh, and Redis durability policy.

## Failure modes {#failures}

Contention returns `null`. Timeout, MOVED/topology, script, authentication, and extension failures propagate. Expired ownership can overlap an old paused action.

## Operations {#operations}

Monitor command latency, reconnects, topology changes, script failures, keyspace/TTL, extension failure, skip rate, and Redis memory pressure.

## Testing {#testing}

Use Redis integration tests for two connections, token-safe release, expiry, group slots, strategic registry, failover handling, and suspend cancellation.

## Workshops and learning path {#workshops}

Run strategic-election for candidate scoring and rate-limiter for group slots; compare Lettuce with Redisson's higher-level client model.

## Limitations {#limitations}

Redis availability and failover semantics are on the job path. A Redis token is not fencing for an external database.

## Sources {#sources}

[Elector](../../../../leader-redis-lettuce/src/main/kotlin/io/bluetape4k/leader/lettuce/LettuceLeaderElector.kt) · [Lua support](../../../../leader-redis-lettuce/src/main/kotlin/io/bluetape4k/leader/lettuce/script/RedisScript.kt) · [Stable guide](../../../../leader-redis-lettuce/README.md)

