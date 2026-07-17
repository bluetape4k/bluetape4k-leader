---
manualId: "bluetape4k-leader-zookeeper"
id: "bluetape4k-leader-zookeeper"
title: "ZooKeeper backend"
locale: "en"
kind: "library"
gradlePath: ":bluetape4k-leader-zookeeper"
sourceDir: "leader-zookeeper"
releaseRef: "0.4.0"
artifact: io.github.bluetape4k.leader:bluetape4k-leader-zookeeper
---

# ZooKeeper backend

> Library module

## Problem {#problem}

Implements single and group election with Apache Curator `InterProcessMutex` and `InterProcessSemaphoreV2`, including blocking, future, and coroutine APIs.

## When to use it {#when-to-use}

Choose it when ZooKeeper/Curator is already a supported coordination plane and session-bound ownership is understood operationally.

## Coordinates {#coordinates}

Artifact: `io.github.bluetape4k.leader:bluetape4k-leader-zookeeper`

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.leader:bluetape4k-leader-zookeeper")
}
```

## Core concepts {#concepts}

Ephemeral recipe nodes disappear when the ZooKeeper session expires. `leaseTime` is accepted for Core API consistency but is not the actual release boundary. Suspend single election preserves Curator's acquire/release owner-thread rule.

## Quick start {#quick-start}

```kotlin
val curator = CuratorFrameworkFactory.newClient(connect, retry).apply { start() }
val elector = ZooKeeperLeaderElector(curator)
elector.runIfLeader("daily-report") { generateReport() }
```

## API by task {#api-by-task}

Use single/group blocking electors, async methods, suspend variants, client extensions, and factories. The Curator client is caller-owned.

## Recommended patterns {#patterns}

Use a dedicated base path, stable session settings, bounded retries, and idempotent actions. Treat session suspension/loss as an ownership event.

## Integrations {#integrations}

Spring can consume factories. zookeeper-scheduler demonstrates a scheduled workload and client lifecycle.

## Configuration {#configuration}

Configure base path, wait time, group size, Curator connection/session timeout, retry policy, ACL, and ensemble endpoints. Do not interpret Core `leaseTime` as ZooKeeper TTL.

## Failure modes {#failures}

Contention skips. Connection suspension/loss, session expiry, ACL, retry exhaustion, and Curator recipe failures propagate. Session loss releases ownership independently of the action.

## Operations {#operations}

Monitor connection state, session expiry, ensemble latency, retries, znode growth, group permits, and skipped work. Close Curator only during application shutdown.

## Testing {#testing}

Use a real ZooKeeper container for session loss, owner-thread release, two-client contention, group permits, extension expectations, async completion, and coroutine cancellation.

## Workshops and learning path {#workshops}

Run zookeeper-scheduler, then compare session-bound ownership with TTL-based Redis and lease-based etcd.

## Limitations {#limitations}

No fixed TTL guarantees release at `leaseTime`; session timeout controls it. ZooKeeper ownership does not fence an external side effect.

<!-- release-readme-diagrams:start -->
## Release diagrams {#release-diagrams}

These diagrams are loaded directly from README assets published with the `0.4.0` release and pinned to its immutable commit. They describe this manual's released structure and runtime flows, not later Snapshot changes. Select a preview to open the SVG at the same release commit.

### leader zookeeper Class Structure diagram

[![leader zookeeper Class Structure diagram](https://raw.githubusercontent.com/bluetape4k/bluetape4k-leader/17ab7f872c1f96318c73d3580729cac20a67e017/docs/images/readme-diagrams/leader-zookeeper-class-01.png)](https://github.com/bluetape4k/bluetape4k-leader/blob/17ab7f872c1f96318c73d3580729cac20a67e017/docs/images/readme-diagrams/leader-zookeeper-class-01.svg)

_Release README: [`leader-zookeeper/README.md`](https://github.com/bluetape4k/bluetape4k-leader/blob/17ab7f872c1f96318c73d3580729cac20a67e017/leader-zookeeper/README.md)_

<!-- release-readme-diagrams:end -->

## Sources {#sources}

[Elector](../../../../leader-zookeeper/src/main/kotlin/io/bluetape4k/leader/zookeeper/ZooKeeperLeaderElector.kt) · [Suspend elector](../../../../leader-zookeeper/src/main/kotlin/io/bluetape4k/leader/zookeeper/ZooKeeperSuspendLeaderElector.kt) · [Stable guide](../../../../leader-zookeeper/README.md)

