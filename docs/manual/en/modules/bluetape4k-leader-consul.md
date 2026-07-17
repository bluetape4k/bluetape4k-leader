---
manualId: "bluetape4k-leader-consul"
id: "bluetape4k-leader-consul"
title: "Consul backend"
locale: "en"
kind: "library"
gradlePath: ":bluetape4k-leader-consul"
sourceDir: "leader-consul"
releaseRef: "0.4.0"
artifact: io.github.bluetape4k.leader:bluetape4k-leader-consul
---

# Consul backend

> Library module

## Problem {#problem}

> **Preview:** Validate API and operational behavior before production adoption.

Preview backend using Consul sessions and KV acquire/release for single and fixed-slot group election. It supports blocking, future, coroutine, and Spring factory surfaces.

## When to use it {#when-to-use}

Use it when Consul is already operated and its session model fits the job. Do not add Consul solely for election without accepting its ACL, session, and watch operations.

## Coordinates {#coordinates}

Artifact: `io.github.bluetape4k.leader:bluetape4k-leader-consul`

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.leader:bluetape4k-leader-consul")
}
```

## Core concepts {#concepts}

A session owns an encoded KV key. Session TTL is 10–86,400 seconds; default `lockDelay` is zero, so expiry may permit overlap with a still-running old holder.

## Quick start {#quick-start}

```kotlin
val elector = ConsulLeaderElector(
    ConsulEndpoint("http://localhost:8500"),
    ConsulLeaderElectionOptions(
        leaderOptions = LeaderElectionOptions(leaseTime = 10.seconds)
    )
)
elector.runIfLeader("daily-report") { generateReport() }
```

## API by task {#api-by-task}

Use `ConsulLeaderElector`/group for blocking code and `ConsulSuspendLeaderElector`/group for coroutines. Caller-owned `ConsulEndpoint` carries URL, datacenter, token, and timeout.

## Recommended patterns {#patterns}

Use an application-specific key prefix, least-privilege ACLs, idempotent actions, and external fencing if overlap is unacceptable.

## Integrations {#integrations}

Spring creates factories from a caller-owned `ConsulEndpoint`. Core listener decorators work, but long-lived blocking-query watches remain application-owned.

## Configuration {#configuration}

Set key/session prefixes, request timeout, TTL-range lease, wait time, group size, and `lockDelay`. The client/session environment is caller-owned.

## Failure modes {#failures}

Contention skips. HTTP, ACL, session, or timeout failures propagate. With zero lock delay, expired ownership can overlap until the old process stops.

## Operations {#operations}

Monitor session renewals, KV latency, ACL failures, orphan sessions, and skip rate. Include datacenter and prefix in runbooks.

## Testing {#testing}

Use Consul integration tests for single/group and blocking/suspend paths; test TTL bounds, owner payload, failure classification, and release.

## Workshops and learning path {#workshops}

Run the consul-maintenance example, then compare Consul with etcd and ZooKeeper by ownership and failure semantics.

## Limitations {#limitations}

Preview status means API/operations may change. Consul's lock is a lease, not fencing; the endpoint and agent lifecycle are not managed by the library.

<!-- release-readme-diagrams:start -->
## Release diagrams {#release-diagrams}

These diagrams are loaded directly from README assets published with the `0.4.0` release and pinned to its immutable commit. They describe this manual's released structure and runtime flows, not later Snapshot changes. Select a preview to open the SVG at the same release commit.

### leader-consul architecture diagram

[![leader-consul architecture diagram](https://raw.githubusercontent.com/bluetape4k/bluetape4k-leader/17ab7f872c1f96318c73d3580729cac20a67e017/docs/images/readme-diagrams/leader-consul-architecture-01.png)](https://github.com/bluetape4k/bluetape4k-leader/blob/17ab7f872c1f96318c73d3580729cac20a67e017/docs/images/readme-diagrams/leader-consul-architecture-01.svg)

_Release README: [`leader-consul/README.md`](https://github.com/bluetape4k/bluetape4k-leader/blob/17ab7f872c1f96318c73d3580729cac20a67e017/leader-consul/README.md)_

### Consul acquire release sequence diagram

[![Consul acquire release sequence diagram](https://raw.githubusercontent.com/bluetape4k/bluetape4k-leader/17ab7f872c1f96318c73d3580729cac20a67e017/docs/images/readme-diagrams/leader-consul-sequence-02.png)](https://github.com/bluetape4k/bluetape4k-leader/blob/17ab7f872c1f96318c73d3580729cac20a67e017/docs/images/readme-diagrams/leader-consul-sequence-02.svg)

_Release README: [`leader-consul/README.md`](https://github.com/bluetape4k/bluetape4k-leader/blob/17ab7f872c1f96318c73d3580729cac20a67e017/leader-consul/README.md)_

<!-- release-readme-diagrams:end -->

## Sources {#sources}

[Elector](../../../../leader-consul/src/main/kotlin/io/bluetape4k/leader/consul/ConsulLeaderElector.kt) · [Options](../../../../leader-consul/src/main/kotlin/io/bluetape4k/leader/consul/ConsulLeaderElectionOptions.kt) · [Stable guide](../../../../leader-consul/README.md)

