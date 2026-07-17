---
title: "Repository and learning map"
description: "Understand which modules define contracts, implement storage, integrate frameworks, and demonstrate complete scenarios."
releaseRef: 0.4.0
releaseCommit: 17ab7f872c1f96318c73d3580729cac20a67e017
---

# Repository and learning map

Understand which modules define contracts, implement storage, integrate frameworks, and demonstrate complete scenarios.

![Leader repository and learning map](../../assets/overview/repository-learning-map.png)

## Four layers

`leader-core` owns API semantics and local implementations. Backend modules implement those contracts against Redis, SQL, document stores, coordination systems, or Kubernetes. Framework modules integrate Spring Boot, Ktor, and Micrometer. The 17 `examples/*` projects combine these pieces into runnable operational scenarios.

## Choose by responsibility

Read core before a backend so contention, cancellation, and lease semantics remain stable when infrastructure changes. Read a framework page only after choosing an elector. Use examples to validate startup, shutdown, metrics, and failure behavior; do not add example projects as dependencies because they are excluded from publication.

## Stable and preview

Release 0.4.0 marks core, Redis, Exposed, MongoDB, Hazelcast, ZooKeeper, framework integrations, and Micrometer as stable. DynamoDB, etcd, Consul, and Kubernetes are preview modules. Preview means the operational contract deserves extra integration tests and rollback planning, not that contention semantics change.

## Release sources

- [`settings.gradle.kts`](../../../../settings.gradle.kts)
- [`README.md`](../../../../README.md)
- [`build.gradle.kts`](../../../../build.gradle.kts)

## Continue learning

- [Bluetape4k Leader manual](../index.md)
- [Learning path](../guides/learning-path.md)
- [Choose a backend](../guides/backend-selection.md)

<!-- release-readme-diagrams:start -->
## Release diagrams {#release-diagrams}

These diagrams are loaded directly from README assets published with the `0.4.0` release and pinned to its immutable commit. They describe this manual's released structure and runtime flows, not later Snapshot changes. Select a preview to open the SVG at the same release commit.

### Leader election runtime map

[![Leader election runtime map](https://raw.githubusercontent.com/bluetape4k/bluetape4k-leader/17ab7f872c1f96318c73d3580729cac20a67e017/docs/images/readme-diagrams/bluetape4k-leader-architecture-01.png)](https://github.com/bluetape4k/bluetape4k-leader/blob/17ab7f872c1f96318c73d3580729cac20a67e017/docs/images/readme-diagrams/bluetape4k-leader-architecture-01.svg)

_Release README: [`README.md`](https://github.com/bluetape4k/bluetape4k-leader/blob/17ab7f872c1f96318c73d3580729cac20a67e017/README.md)_

### How runIfLeader Works diagram

[![How runIfLeader Works diagram](https://raw.githubusercontent.com/bluetape4k/bluetape4k-leader/17ab7f872c1f96318c73d3580729cac20a67e017/docs/images/readme-diagrams/bluetape4k-leader-sequence-02.png)](https://github.com/bluetape4k/bluetape4k-leader/blob/17ab7f872c1f96318c73d3580729cac20a67e017/docs/images/readme-diagrams/bluetape4k-leader-sequence-02.svg)

_Release README: [`README.md`](https://github.com/bluetape4k/bluetape4k-leader/blob/17ab7f872c1f96318c73d3580729cac20a67e017/README.md)_

### Multi-leader group: slot-based semaphore diagram

[![Multi-leader group: slot-based semaphore diagram](https://raw.githubusercontent.com/bluetape4k/bluetape4k-leader/17ab7f872c1f96318c73d3580729cac20a67e017/docs/images/readme-diagrams/bluetape4k-leader-sequence-03.png)](https://github.com/bluetape4k/bluetape4k-leader/blob/17ab7f872c1f96318c73d3580729cac20a67e017/docs/images/readme-diagrams/bluetape4k-leader-sequence-03.svg)

_Release README: [`README.md`](https://github.com/bluetape4k/bluetape4k-leader/blob/17ab7f872c1f96318c73d3580729cac20a67e017/README.md)_

### Bluetape4k Leader overview diagram

[![Bluetape4k Leader overview diagram](https://raw.githubusercontent.com/bluetape4k/bluetape4k-leader/17ab7f872c1f96318c73d3580729cac20a67e017/docs/images/readme-diagrams/root-readme-overview-01.png)](https://github.com/bluetape4k/bluetape4k-leader/blob/17ab7f872c1f96318c73d3580729cac20a67e017/docs/images/readme-diagrams/root-readme-overview-01.svg)

_Release README: [`README.md`](https://github.com/bluetape4k/bluetape4k-leader/blob/17ab7f872c1f96318c73d3580729cac20a67e017/README.md)_

<!-- release-readme-diagrams:end -->
