---
manualId: "bluetape4k-leader-exposed-jdbc"
id: "bluetape4k-leader-exposed-jdbc"
title: "Exposed JDBC backend"
locale: "en"
kind: "library"
gradlePath: ":bluetape4k-leader-exposed-jdbc"
sourceDir: "leader-exposed-jdbc"
releaseRef: "0.4.0"
artifact: io.github.bluetape4k.leader:bluetape4k-leader-exposed-jdbc
---

# Exposed JDBC backend

> Library module

## Problem {#problem}

Implements single and group election with Exposed JDBC transactions for blocking and virtual-thread services.

## When to use it {#when-to-use}

Choose it when an operated relational database should also coordinate jobs and an extra coordination service is unjustified.

## Coordinates {#coordinates}

Artifact: `io.github.bluetape4k.leader:bluetape4k-leader-exposed-jdbc`

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.leader:bluetape4k-leader-exposed-jdbc")
}
```

## Core concepts {#concepts}

Conditional row transitions acquire leases; expiry and owner tokens stop an old holder releasing a newer lease.

## Quick start {#quick-start}

```kotlin
val elector = ExposedJdbcLeaderElector(database)
elector.runIfLeader("invoice-close") { closeInvoices() }
```

## API by task {#api-by-task}

Use blocking, group, factory, extension, history-sink, or `ExposedJdbcVirtualThreadLeaderElector` APIs by task.

## Recommended patterns {#patterns}

Create schema before traffic, keep acquire transactions short, and separate the protected business transaction unless atomic coupling is deliberate.

## Integrations {#integrations}

Works with the release's Exposed JDBC databases. Spring can build factories from a `Database` bean.

## Configuration {#configuration}

Tune wait, lease, minimum lease, retry, schema, isolation, statement timeout, and pool capacity.

## Failure modes {#failures}

Contention returns `null`; connection, permission, schema, rollback, and retry-exhaustion failures propagate.

## Operations {#operations}

Watch database latency, pool saturation, retries, cleanup, indexes, and clock consistency.

## Testing {#testing}

Use the production dialect in Testcontainers and test two connections, expiry, stale-owner release, group capacity, and rollback.

## Workshops and learning path {#workshops}

Run migration-gate, compare R2DBC, then use batch-scheduler for a Spring job.

## Limitations {#limitations}

The database is on every acquire path. Lease and business action are not one transaction unless explicitly designed that way.

## Sources {#sources}

[Elector](../../../../leader-exposed-jdbc/src/main/kotlin/io/bluetape4k/leader/exposed/jdbc/ExposedJdbcLeaderElector.kt) · [Initializer](../../../../leader-exposed-jdbc/src/main/kotlin/io/bluetape4k/leader/exposed/jdbc/lock/ExposedJdbcSchemaInitializer.kt) · [Stable guide](../../../../leader-exposed-jdbc/README.md)

