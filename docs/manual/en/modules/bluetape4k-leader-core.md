---
manualId: "bluetape4k-leader-core"
id: "bluetape4k-leader-core"
title: "Leader core library"
locale: "en"
kind: "library"
gradlePath: ":bluetape4k-leader-core"
sourceDir: "leader-core"
releaseRef: "0.4.0"
artifact: io.github.bluetape4k.leader:bluetape4k-leader-core
---

# Leader core library

> Library module

## Problem {#problem}

Defines the common election contract and local implementations for blocking, `CompletableFuture`, virtual-thread, and coroutine code. Contention returns `null`; action failures propagate.

## When to use it {#when-to-use}

Use local electors for one-JVM coordination and tests. Select a backend module for cross-process ownership.

## Coordinates {#coordinates}

Artifact: `io.github.bluetape4k.leader:bluetape4k-leader-core`

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.leader:bluetape4k-leader-core")
}
```

## Core concepts {#concepts}

Single election protects one lock; group election admits up to `maxLeaders` fixed slots. `LeaderRunResult` separates `Elected`, `Skipped`, and `ActionFailed`.

## Quick start {#quick-start}

```kotlin
val elector = LocalLeaderElector(
    LeaderElectionOptions(waitTime = 500.milliseconds, leaseTime = 30.seconds)
)
val result = elector.runIfLeader("daily-report") { generateReport() }
```

## API by task {#api-by-task}

Use `runIfLeaderResult` when `null` is a valid action result. Use suspend, async, virtual-thread, group, or strategic electors according to the caller's execution model.

## Recommended patterns {#patterns}

Keep lock names stable and business-scoped. Make actions idempotent, size leases above normal duration, and use `autoExtend` only on supported single-leader paths.

## Integrations {#integrations}

All backends implement these interfaces. Spring maps annotations to factories, Ktor schedules suspend actions, and Micrometer decorates outcomes.

## Configuration {#configuration}

`waitTime` bounds acquisition, `leaseTime` is backend-specific, and `minLeaseTime` keeps a successful lock for a minimum duration.

## Failure modes {#failures}

Contention is `null`/`Skipped`; backend failures are exceptions. Cancellation is rethrown, and blocking interruption restores the interrupt flag.

## Operations {#operations}

Measure acquisition, skip, action failure, backend failure, and extension failure separately. Listener lease metadata is not proof of ownership.

## Testing {#testing}

Cover winner/loser behavior, action exceptions, cancellation, release, group capacity, and listener order. Distributed backends still need real integration tests.

## Workshops and learning path {#workshops}

Read the lifecycle and model-selection guides, then run batch-scheduler and strategic-election examples before choosing a backend.

## Limitations {#limitations}

Local electors coordinate only one JVM. A distributed lease cannot roll back external side effects; use idempotency or fencing where duplicates are unsafe.

<!-- release-readme-diagrams:start -->
## Release diagrams {#release-diagrams}

These diagrams are copied byte-for-byte from README assets in the `0.4.0` release tag. They describe this manual's released structure and runtime flows, not later Snapshot changes. Select a preview to open the SVG source.

### leader-core API contract map

[![leader-core API contract map](../../assets/readme-diagrams/leader-core-class-01.png)](../../assets/readme-diagrams/leader-core-class-01.svg)

_Release README: [`leader-core/README.md`](https://github.com/bluetape4k/bluetape4k-leader/blob/17ab7f872c1f96318c73d3580729cac20a67e017/leader-core/README.md)_

### Single-leader runIfLeader flow

[![Single-leader runIfLeader flow](../../assets/readme-diagrams/leader-core-sequence-02.png)](../../assets/readme-diagrams/leader-core-sequence-02.svg)

_Release README: [`leader-core/README.md`](https://github.com/bluetape4k/bluetape4k-leader/blob/17ab7f872c1f96318c73d3580729cac20a67e017/leader-core/README.md)_

### Group-leader slot flow

[![Group-leader slot flow](../../assets/readme-diagrams/leader-core-sequence-03.png)](../../assets/readme-diagrams/leader-core-sequence-03.svg)

_Release README: [`leader-core/README.md`](https://github.com/bluetape4k/bluetape4k-leader/blob/17ab7f872c1f96318c73d3580729cac20a67e017/leader-core/README.md)_

<!-- release-readme-diagrams:end -->

## Sources {#sources}

[Core contract](../../../../leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderElector.kt) · [Options](../../../../leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderElectionOptions.kt) · [Contract test](../../../../leader-core/src/test/kotlin/io/bluetape4k/leader/LeaderRunResultTest.kt)

