---
manualId: "bluetape4k-leader-exposed-r2dbc"
id: "bluetape4k-leader-exposed-r2dbc"
title: "Exposed R2DBC backend"
locale: "en"
kind: "library"
gradlePath: ":bluetape4k-leader-exposed-r2dbc"
sourceDir: "leader-exposed-r2dbc"
releaseRef: "0.4.0"
artifact: io.github.bluetape4k.leader:bluetape4k-leader-exposed-r2dbc
---

# Exposed R2DBC backend

> Library module

## Problem {#problem}

Implements coroutine single and group election with Exposed R2DBC transactions while sharing SQL ownership rules with JDBC.

## When to use it {#when-to-use}

Choose it for coroutine-first R2DBC services; choose JDBC when surrounding APIs are blocking or virtual-thread based.

## Coordinates {#coordinates}

Artifact: `io.github.bluetape4k.leader:bluetape4k-leader-exposed-r2dbc`

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.leader:bluetape4k-leader-exposed-r2dbc")
}
```

## Core concepts {#concepts}

Suspend transactions perform conditional writes with expiry and owner tokens. Cancellation must clean up only the lease owned by that call.

## Quick start {#quick-start}

```kotlin
val elector = ExposedR2DbcSuspendLeaderElector(database)
elector.runIfLeader("projection-refresh") { refreshProjection() }
```

## API by task {#api-by-task}

Use suspend single/group electors, `R2dbcDatabase` extensions, factories, initializer, and suspend history sink.

## Recommended patterns {#patterns}

Keep acquire/use/release in structured scope, do not hide JDBC blocking calls inside it, and migrate the shared schema first.

## Integrations {#integrations}

Can share a namespace with JDBC when database, schema, and release match. Spring and Ktor consume the suspend factory/elector.

## Configuration {#configuration}

Tune wait, lease, retry, schema, R2DBC pool, statement timeout, and lifecycle ownership.

## Failure modes {#failures}

Contention returns `null`; cancellation rethrows after owner-safe cleanup; connection and transaction failures propagate.

## Operations {#operations}

Observe acquisition latency, pool queue time, retries, cancellation cleanup, and database load.

## Testing {#testing}

Use a real R2DBC database for two-client contention, group slots, expiry, and cancellation during acquire/action.

## Workshops and learning path {#workshops}

Compare JDBC, then run migration-gate and the Ktor example to see lifecycle integration.

## Limitations {#limitations}

R2DBC removes blocked threads, not database round trips or database availability risk.

<!-- release-readme-diagrams:start -->
## Release diagrams {#release-diagrams}

These diagrams are copied byte-for-byte from README assets in the `0.4.0` release tag. They describe this manual's released structure and runtime flows, not later Snapshot changes. Select a preview to open the SVG source.

### leader exposed r2dbc Class Structure diagram

[![leader exposed r2dbc Class Structure diagram](../../assets/readme-diagrams/leader-exposed-r2dbc-class-01.png)](../../assets/readme-diagrams/leader-exposed-r2dbc-class-01.svg)

_Release README: [`leader-exposed-r2dbc/README.md`](https://github.com/bluetape4k/bluetape4k-leader/blob/17ab7f872c1f96318c73d3580729cac20a67e017/leader-exposed-r2dbc/README.md)_

<!-- release-readme-diagrams:end -->

## Sources {#sources}

[Elector](../../../../leader-exposed-r2dbc/src/main/kotlin/io/bluetape4k/leader/exposed/r2dbc/ExposedR2DbcSuspendLeaderElector.kt) · [Initializer](../../../../leader-exposed-r2dbc/src/main/kotlin/io/bluetape4k/leader/exposed/r2dbc/lock/ExposedR2dbcSchemaInitializer.kt) · [Stable guide](../../../../leader-exposed-r2dbc/README.md)

