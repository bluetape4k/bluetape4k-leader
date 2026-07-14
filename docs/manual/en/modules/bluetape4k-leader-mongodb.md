---
manualId: "bluetape4k-leader-mongodb"
id: "bluetape4k-leader-mongodb"
title: "MongoDB backend"
locale: "en"
kind: "library"
gradlePath: ":bluetape4k-leader-mongodb"
sourceDir: "leader-mongodb"
releaseRef: "0.4.0"
artifact: io.github.bluetape4k.leader:bluetape4k-leader-mongodb
---

# MongoDB backend

> Library module

## Problem {#problem}

Implements single/group election with token-owned MongoDB documents, atomic `findOneAndUpdate`, expiry, TTL indexes, and blocking/coroutine factories.

## When to use it {#when-to-use}

Choose it when MongoDB is already a durable application dependency and coordination in the same replica set is operationally simpler.

## Coordinates {#coordinates}

Artifact: `io.github.bluetape4k.leader:bluetape4k-leader-mongodb`

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.leader:bluetape4k-leader-mongodb")
}
```

## Core concepts {#concepts}

The collection stores lock documents with owner token and `expireAt`; atomic filters acquire and extend. TTL index cleanup is not the correctness clock.

## Quick start {#quick-start}

```kotlin
val collection = database.getCollection<Document>("bluetape4k_leader_locks")
val elector = MongoLeaderElector(collection)
elector.runIfLeader("tenant-aggregation") { aggregate() }
```

## API by task {#api-by-task}

Use blocking/suspend single and group electors, factories, extensions, and optional Mongo history sink/indexer. Collection lifecycle is caller-owned.

## Recommended patterns {#patterns}

Use majority write concern in replica sets, stable collection/name prefixes, idempotent actions, and owner-token conditions on extend/release.

## Integrations {#integrations}

Spring can build factories from a `MongoClient`. tenant-aggregator demonstrates a partitioned workload.

## Configuration {#configuration}

Configure database/collection, wait/lease/minimum lease, retry delay, group size, write concern, and history TTL/index policy.

## Failure modes {#failures}

Contention returns `null`. Network, primary election, write-concern, permission, and index errors propagate. TTL deletion delay must not control acquisition.

## Operations {#operations}

Monitor command latency, write concern errors, primary changes, retry rate, stale documents, TTL monitor lag, extension failures, and history growth.

## Testing {#testing}

Use a replica-set Testcontainer for two-client contention, failover behavior, TTL/expiry, stale owner, group slots, suspend cancellation, and index creation.

## Workshops and learning path {#workshops}

Run tenant-aggregator, then compare MongoDB with SQL when deciding document versus transactional coordination.

## Limitations {#limitations}

Replica-set configuration affects split-brain risk. MongoDB lease ownership does not fence writes to another system.

## Sources {#sources}

[Elector](../../../../leader-mongodb/src/main/kotlin/io/bluetape4k/leader/mongodb/MongoLeaderElector.kt) · [Lock implementation](../../../../leader-mongodb/src/main/kotlin/io/bluetape4k/leader/mongodb/lock/MongoLock.kt) · [Stable guide](../../../../leader-mongodb/README.md)

