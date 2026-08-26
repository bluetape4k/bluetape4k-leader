---
manualId: "bluetape4k-leader-ktor"
id: "bluetape4k-leader-ktor"
title: "Ktor integration"
locale: "en"
kind: "library"
gradlePath: ":bluetape4k-leader-ktor"
sourceDir: "leader-ktor"
releaseRef: "0.5.0"
artifact: io.github.bluetape4k.leader:bluetape4k-leader-ktor
---

# Ktor integration

> Library module

## Problem {#problem}

Integrates a suspend elector with Ktor 3 through `LeaderElectionPlugin`, `leaderScheduled`, and an optional management route. It binds job lifetime to the application lifecycle.

## When to use it {#when-to-use}

Use it when a Ktor service owns coroutine jobs that should run on only one node. Use Spring integration for proxy/annotation-driven methods.

## Coordinates {#coordinates}

Artifact: `io.github.bluetape4k.leader:bluetape4k-leader-ktor`

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.leader:bluetape4k-leader-ktor")
}
```

## Core concepts {#concepts}

The plugin resolves one `SuspendLeaderElector`; scheduled jobs run in application-owned coroutine scope and stop on shutdown. Contention skips the iteration rather than failing the server.

## Quick start {#quick-start}

```kotlin
install(LeaderElectionPlugin) {
    leaderElection = mySuspendElector
}
leaderScheduled("projection-refresh", 1.minutes) {
    refreshProjection()
}
```

## API by task {#api-by-task}

Install the plugin for elector ownership, call `leaderScheduled` for periodic suspend work, and expose management routes only under an authenticated operations boundary.

## Recommended patterns {#patterns}

Install once, choose stable lock names, keep actions shorter than the lease or extend safely, and make shutdown cancellation part of the job contract.

## Integrations {#integrations}

Any suspend backend can be supplied. The ktor-app example shows plugin installation, scheduling, and lifecycle together.

## Configuration {#configuration}

Configure the elector in its backend module, then set schedule interval/delay and Ktor scope ownership. Do not create a hidden second client inside each job.

## Failure modes {#failures}

A missing plugin or elector is a startup configuration failure. Direct elector calls propagate backend and action failures. `Application.leaderScheduled` catches non-cancellation `Exception`, logs it at WARN, suppresses that iteration, and continues with the next cycle; normal contention still skips. Cancellation stops scheduling and lets the elector release owned state.

## Operations {#operations}

Measure scheduled attempts, elected runs, skips, failures, duration, and shutdown completion. Keep management endpoints authenticated and low-cardinality.

## Testing {#testing}

Use Ktor test application for plugin configuration and schedule lifecycle, plus backend integration tests for ownership. Verify shutdown during acquire and action.

## Workshops and learning path {#workshops}

Run ktor-app, then follow the chosen backend page. Compare with Spring when deciding explicit scheduling versus AOP annotations.

## Limitations {#limitations}

The integration schedules jobs; it does not provide durable scheduling, missed-run recovery, cron persistence, or exactly-once delivery.

<!-- release-readme-diagrams:start -->
## Release diagrams {#release-diagrams}

These diagrams are loaded directly from README assets published with the `0.5.0` release and pinned to its immutable commit. They describe this manual's released structure and runtime flows, not later Snapshot changes. Select a preview to open the SVG at the same release commit.

### leader ktor Architecture diagram

[![leader ktor Architecture diagram](https://raw.githubusercontent.com/bluetape4k/bluetape4k-leader/721a9a3808f67489d2bdb8177734325981c24977/docs/images/readme-diagrams/leader-ktor-architecture-01.png)](https://github.com/bluetape4k/bluetape4k-leader/blob/721a9a3808f67489d2bdb8177734325981c24977/docs/images/readme-diagrams/leader-ktor-architecture-01.svg)

_Release README: [`leader-ktor/README.md`](https://github.com/bluetape4k/bluetape4k-leader/blob/721a9a3808f67489d2bdb8177734325981c24977/leader-ktor/README.md)_

### leader ktor Sequence Flow diagram

[![leader ktor Sequence Flow diagram](https://raw.githubusercontent.com/bluetape4k/bluetape4k-leader/721a9a3808f67489d2bdb8177734325981c24977/docs/images/readme-diagrams/leader-ktor-sequence-01.png)](https://github.com/bluetape4k/bluetape4k-leader/blob/721a9a3808f67489d2bdb8177734325981c24977/docs/images/readme-diagrams/leader-ktor-sequence-01.svg)

_Release README: [`leader-ktor/README.md`](https://github.com/bluetape4k/bluetape4k-leader/blob/721a9a3808f67489d2bdb8177734325981c24977/leader-ktor/README.md)_

<!-- release-readme-diagrams:end -->

## Sources {#sources}

[Plugin](../../../../leader-ktor/src/main/kotlin/io/bluetape4k/leader/ktor/LeaderElectionPlugin.kt) · [Scheduling extension](../../../../leader-ktor/src/main/kotlin/io/bluetape4k/leader/ktor/ApplicationExt.kt) · [Stable guide](../../../../leader-ktor/README.md)
