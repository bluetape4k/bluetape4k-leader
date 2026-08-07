---
manualId: "bluetape4k-leader-etcd"
id: "bluetape4k-leader-etcd"
title: "etcd backend"
locale: "en"
kind: "library"
gradlePath: ":bluetape4k-leader-etcd"
sourceDir: "leader-etcd"
releaseRef: "0.5.0"
artifact: io.github.bluetape4k.leader:bluetape4k-leader-etcd
---

# etcd backend

> Library module

## Problem {#problem}

> **Preview:** Validate API and operational behavior before production adoption.

Preview backend using etcd v3 leases and the jetcd Lock service for single and group election, with blocking, future, coroutine, and virtual-thread surfaces.

## When to use it {#when-to-use}

Use it when etcd is already a reliable control-plane dependency and lease semantics fit the workload.

## Coordinates {#coordinates}

Artifact: `io.github.bluetape4k.leader:bluetape4k-leader-etcd`

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.leader:bluetape4k-leader-etcd")
}
```

## Core concepts {#concepts}

Acquisition creates a lease and lock key; owner-safe release and extension use the returned token. The supplied jetcd client is caller-owned.

## Quick start {#quick-start}

```kotlin
val elector = EtcdLeaderElector(
    client,
    EtcdLeaderElectionOptions(keyPrefix = "/apps/orders/leader")
)
elector.runIfLeader("reconcile") { reconcile() }
```

## API by task {#api-by-task}

Use `EtcdLeaderElector`, suspend, virtual-thread, or group variants. Factories supply Core and framework integrations.

## Recommended patterns {#patterns}

Use a dedicated prefix and least-privilege credentials. Keep actions idempotent and account for lease loss during pauses or partitions.

## Integrations {#integrations}

Spring can construct factories from a client. The etcd-reconciler example demonstrates a control-loop workload.

## Configuration {#configuration}

Configure endpoint in the caller-owned client, key prefix, wait/lease/minimum lease, retry delay, and cleanup timeout budget.

## Failure modes {#failures}

Contention skips. Lease grant, keepalive, lock, cleanup timeout, authentication, and transport failures propagate after classification.

## Operations {#operations}

Watch lease keepalive, request latency, compaction/cluster health, cleanup failures, and skipped work. Client closure belongs to the application.

## Testing {#testing}

Use a real etcd container for acquire/release, lease expiry, key encoding, cleanup timeout, group slots, and cancellation.

## Workshops and learning path {#workshops}

Run etcd-reconciler, then compare etcd leases with Consul sessions and Kubernetes Lease objects.

## Limitations {#limitations}

Preview API may evolve. etcd availability is now on the job path; a lease is not a business-side fencing token.

<!-- release-readme-diagrams:start -->
## Release diagrams {#release-diagrams}

These diagrams are loaded directly from README assets published with the `0.5.0` release and pinned to its immutable commit. They describe this manual's released structure and runtime flows, not later Snapshot changes. Select a preview to open the SVG at the same release commit.

### leader-etcd architecture diagram

[![leader-etcd architecture diagram](https://raw.githubusercontent.com/bluetape4k/bluetape4k-leader/721a9a3808f67489d2bdb8177734325981c24977/docs/images/readme-diagrams/leader-etcd-architecture-01.png)](https://github.com/bluetape4k/bluetape4k-leader/blob/721a9a3808f67489d2bdb8177734325981c24977/docs/images/readme-diagrams/leader-etcd-architecture-01.svg)

_Release README: [`leader-etcd/README.md`](https://github.com/bluetape4k/bluetape4k-leader/blob/721a9a3808f67489d2bdb8177734325981c24977/leader-etcd/README.md)_

<!-- release-readme-diagrams:end -->

## Sources {#sources}

[Elector](../../../../leader-etcd/src/main/kotlin/io/bluetape4k/leader/etcd/EtcdLeaderElector.kt) · [Options](../../../../leader-etcd/src/main/kotlin/io/bluetape4k/leader/etcd/EtcdLeaderElectionOptions.kt) · [Stable guide](../../../../leader-etcd/README.md)

