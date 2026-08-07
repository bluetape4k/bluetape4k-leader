---
manualId: "bluetape4k-leader-hazelcast"
id: "bluetape4k-leader-hazelcast"
title: "Hazelcast backend"
locale: "en"
kind: "library"
gradlePath: ":bluetape4k-leader-hazelcast"
sourceDir: "leader-hazelcast"
releaseRef: "0.5.0"
artifact: io.github.bluetape4k.leader:bluetape4k-leader-hazelcast
---

# Hazelcast backend

> Library module

## Problem {#problem}

Implements single and fixed-slot group election with Hazelcast `IMap` TTL entries and owner tokens. Blocking, future, and coroutine paths share the Core skip-on-contention contract.

## When to use it {#when-to-use}

Choose it when the application already operates a Hazelcast cluster and wants coordination in the same failure domain.

## Coordinates {#coordinates}

Artifact: `io.github.bluetape4k.leader:bluetape4k-leader-hazelcast`

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.leader:bluetape4k-leader-hazelcast")
}
```

## Core concepts {#concepts}

`putIfAbsent` plus TTL acquires a token-owned entry. The lock is not thread-bound; extend and release must still verify the token so an old holder cannot modify a newer lease.

## Quick start {#quick-start}

```kotlin
val elector = HazelcastLeaderElector(
    hazelcastInstance,
    LeaderElectionOptions(leaseTime = 30.seconds)
)
elector.runIfLeader("cache-warmer") { warmCache() }
```

## API by task {#api-by-task}

Use single/group electors and suspend variants; factories connect framework integrations. State APIs read the shared map but do not grant execution rights.

## Recommended patterns {#patterns}

Keep the Hazelcast instance caller-owned, use stable lock names, leave capacity for election maps, and make protected work idempotent.

## Integrations {#integrations}

Spring factories can use a `HazelcastInstance`. The cache-warmer example demonstrates an ordinary scheduled workload.

## Configuration {#configuration}

Tune wait, lease, minimum lease, group size, cluster discovery, split-brain protection, and map backup policy. The default single-lock map is `bluetape4k:leader:locks`.

## Failure modes {#failures}

Contention returns `null`. Cluster disconnects, serialization/configuration errors, and owner-safe extend/release failures propagate. TTL expiry can overlap a paused old action.

## Operations {#operations}

Monitor cluster membership, partition migration, map operation latency, backup health, extension failures, and skip rate.

## Testing {#testing}

Use a multi-member test cluster for contention, partition/membership changes, TTL expiry, owner-token release, group slots, and suspend cancellation.

## Workshops and learning path {#workshops}

Run cache-warmer, then compare the in-memory distributed model with Redis and MongoDB backends.

## Limitations {#limitations}

Hazelcast availability and split-brain policy become election dependencies. TTL ownership is not an external fencing token.

<!-- release-readme-diagrams:start -->
## Release diagrams {#release-diagrams}

These diagrams are loaded directly from README assets published with the `0.5.0` release and pinned to its immutable commit. They describe this manual's released structure and runtime flows, not later Snapshot changes. Select a preview to open the SVG at the same release commit.

### leader-hazelcast implementation structure diagram

[![leader-hazelcast implementation structure diagram](https://raw.githubusercontent.com/bluetape4k/bluetape4k-leader/721a9a3808f67489d2bdb8177734325981c24977/docs/images/readme-diagrams/leader-hazelcast-class-01.png)](https://github.com/bluetape4k/bluetape4k-leader/blob/721a9a3808f67489d2bdb8177734325981c24977/docs/images/readme-diagrams/leader-hazelcast-class-01.svg)

_Release README: [`leader-hazelcast/README.md`](https://github.com/bluetape4k/bluetape4k-leader/blob/721a9a3808f67489d2bdb8177734325981c24977/leader-hazelcast/README.md)_

### Lock acquire/release sequence diagram

[![Lock acquire/release sequence diagram](https://raw.githubusercontent.com/bluetape4k/bluetape4k-leader/721a9a3808f67489d2bdb8177734325981c24977/docs/images/readme-diagrams/leader-hazelcast-sequence-02.png)](https://github.com/bluetape4k/bluetape4k-leader/blob/721a9a3808f67489d2bdb8177734325981c24977/docs/images/readme-diagrams/leader-hazelcast-sequence-02.svg)

_Release README: [`leader-hazelcast/README.md`](https://github.com/bluetape4k/bluetape4k-leader/blob/721a9a3808f67489d2bdb8177734325981c24977/leader-hazelcast/README.md)_

### Group election slot sequence (maxLeaders = N) diagram

[![Group election slot sequence (maxLeaders = N) diagram](https://raw.githubusercontent.com/bluetape4k/bluetape4k-leader/721a9a3808f67489d2bdb8177734325981c24977/docs/images/readme-diagrams/leader-hazelcast-sequence-03.png)](https://github.com/bluetape4k/bluetape4k-leader/blob/721a9a3808f67489d2bdb8177734325981c24977/docs/images/readme-diagrams/leader-hazelcast-sequence-03.svg)

_Release README: [`leader-hazelcast/README.md`](https://github.com/bluetape4k/bluetape4k-leader/blob/721a9a3808f67489d2bdb8177734325981c24977/leader-hazelcast/README.md)_

<!-- release-readme-diagrams:end -->

## Sources {#sources}

[Elector](../../../../leader-hazelcast/src/main/kotlin/io/bluetape4k/leader/hazelcast/HazelcastLeaderElector.kt) · [Lock implementation](../../../../leader-hazelcast/src/main/kotlin/io/bluetape4k/leader/hazelcast/lock/HazelcastLock.kt) · [Stable guide](../../../../leader-hazelcast/README.md)

