---
manualId: "bluetape4k-leader-ktor"
id: "bluetape4k-leader-ktor"
title: "Ktor integration"
locale: "en"
kind: "library"
gradlePath: ":bluetape4k-leader-ktor"
sourceDir: "leader-ktor"
releaseRef: "0.4.0"
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

Missing plugin/elector is startup configuration failure. Backend failures and action failures surface; normal contention skips. Cancellation should stop scheduling and release owned state.

## Operations {#operations}

Measure scheduled attempts, elected runs, skips, failures, duration, and shutdown completion. Keep management endpoints authenticated and low-cardinality.

## Testing {#testing}

Use Ktor test application for plugin configuration and schedule lifecycle, plus backend integration tests for ownership. Verify shutdown during acquire and action.

## Workshops and learning path {#workshops}

Run ktor-app, then follow the chosen backend page. Compare with Spring when deciding explicit scheduling versus AOP annotations.

## Limitations {#limitations}

The integration schedules jobs; it does not provide durable scheduling, missed-run recovery, cron persistence, or exactly-once delivery.

## Sources {#sources}

[Plugin](../../../../leader-ktor/src/main/kotlin/io/bluetape4k/leader/ktor/LeaderElectionPlugin.kt) · [Scheduling extension](../../../../leader-ktor/src/main/kotlin/io/bluetape4k/leader/ktor/ApplicationExt.kt) · [Stable guide](../../../../leader-ktor/README.md)
