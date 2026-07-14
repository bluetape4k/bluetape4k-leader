---
manualId: "bluetape4k-leader-dynamodb"
id: "bluetape4k-leader-dynamodb"
title: "DynamoDB backend"
locale: "en"
kind: "library"
gradlePath: ":bluetape4k-leader-dynamodb"
sourceDir: "leader-dynamodb"
releaseRef: "0.4.0"
artifact: io.github.bluetape4k.leader:bluetape4k-leader-dynamodb
---

# DynamoDB backend

> Library module

## Problem {#problem}

> **Preview:** Validate API and operational behavior before production adoption.

Preview backend using DynamoDB conditional writes and logical lease expiry. It supports blocking, async, virtual-thread, coroutine, and group election.

## When to use it {#when-to-use}

Use it for AWS workloads that already depend on DynamoDB and need region-local coordination without another service.

## Coordinates {#coordinates}

Artifact: `io.github.bluetape4k.leader:bluetape4k-leader-dynamodb`

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.leader:bluetape4k-leader-dynamodb")
}
```

## Core concepts {#concepts}

The table has string partition key `lockName`; rows store owner IDs, millisecond `leaseExpiry`, and second `ttl`. Correctness uses `leaseExpiry`; DynamoDB TTL is cleanup metadata only.

## Quick start {#quick-start}

```kotlin
val elector = DynamoDbLeaderElector(
    dynamoDbClient,
    DynamoDbLeaderElectionOptions(tableName = "bluetape4k_leader_locks")
)
elector.runIfLeader("export") { exportData() }
```

## API by task {#api-by-task}

Choose sync, async-client suspend, virtual-thread, or group classes. The application provisions the table and owns both AWS clients.

## Recommended patterns {#patterns}

Use PAY_PER_REQUEST or capacity sized for contention, stable key prefixes, idempotent actions, and conditional owner checks for extend/release.

## Integrations {#integrations}

Spring auto-configuration can use caller-owned AWS clients. The dynamodb-export example shows a complete job lifecycle.

## Configuration {#configuration}

Configure table name, key prefix, wait/lease/minimum lease, retry behavior, and TTL attribute. Enable TTL on numeric `ttl` for cleanup.

## Failure modes {#failures}

Conditional-check contention skips; SDK, IAM, throttling, and network failures propagate. TTL deletion delay must never decide whether acquisition is safe.

## Operations {#operations}

Monitor consumed capacity, throttles, conditional failures, latency, stale-row count, and clock skew. Keep the table lifecycle in infrastructure code.

## Testing {#testing}

Use DynamoDB Local/Testcontainers for two-client acquire, expiry, stale-owner release, group slots, suspend and virtual-thread paths.

## Workshops and learning path {#workshops}

Run dynamodb-export and compare the AWS operational model with Redis or SQL backends.

## Limitations {#limitations}

Preview status and eventually executed TTL cleanup require conservative operations. Cross-region coordination and global-table conflict behavior are outside this contract.

## Sources {#sources}

[Elector](../../../../leader-dynamodb/src/main/kotlin/io/bluetape4k/leader/dynamodb/DynamoDbLeaderElector.kt) · [Options](../../../../leader-dynamodb/src/main/kotlin/io/bluetape4k/leader/dynamodb/DynamoDbLeaderElectionOptions.kt) · [Stable guide](../../../../leader-dynamodb/README.md)

