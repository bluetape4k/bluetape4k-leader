# examples-webhook-poller

[한국어](./README.ko.md) | English

Distributed webhook event poller using MongoDB leader election. Demonstrates safe single-leader claim of webhook events across N pods, with at-least-once delivery, retry, and a `FAILED` terminal state as DLQ substitute.

## Architecture

![Architecture 1](../../docs/images/readme-diagrams/examples-webhook-poller-diagram-01.svg)

## Core Features

- Single-leader polling across N pollers — only the leader claims and processes
- Atomic claim via `findOneAndUpdate` — no double-processing under contention
- Lease-based reclaim — if a leader dies, expired CLAIMED events are picked up by the next leader
- Retry with `maxAttempts` cap — on exceeding the cap, event transitions to `FAILED` (DLQ substitute)
- `attempts` counter increments on claim only — single source of truth, no double-counting
- Backed by `MongoSuspendLeaderElector` (TTL + token-based lock) — coroutine-safe

## Usage Example

```kotlin
val elector = MongoSuspendLeaderElector(lockCollection)
val poller = WebhookPoller(
    elector = elector,
    eventCollection = eventCollection,
    options = WebhookPollerOptions(
        nodeId = System.getenv("HOSTNAME"),
        lockName = "webhook-poller:prod",
        pollInterval = 1.seconds,
        batchSize = 10,
        maxAttempts = 5,
        claimDuration = 30.seconds,    // must exceed worst-case handler runtime
        leaseTime = 60.seconds,         // leader-lock TTL
        waitTime = 1.seconds,
    ),
) { event ->
    httpClient.post(event.payload)     // forward webhook
}

val job = poller.start(applicationScope)
// ... shutdown ...
poller.stopGracefully(timeout = 30.seconds)
```

## Demo

```bash
MONGO_URL=mongodb://localhost:27017 ./gradlew :examples:webhook-poller:run
```

Inserts 10 fake events into a fresh collection, then runs 3 pollers concurrently. Expected: each event handled exactly once, no duplicates.

## Configuration Options

| Parameter | Default | Description |
|-----------|---------|-------------|
| `nodeId` | required | Pod identifier — written to `claimedBy` for tracing |
| `lockName` | required | Distributed leader-lock key — recommend per-collection |
| `pollInterval` | `1.seconds` | Sleep between batch cycles when leader |
| `batchSize` | `10` | Max events claimed per cycle |
| `maxAttempts` | `5` | Retry cap — on exceeding, event → `FAILED` |
| `claimDuration` | `30.seconds` | Lease for an in-flight claim — must exceed handler runtime |
| `leaseTime` | `60.seconds` | Leader-lock TTL |
| `waitTime` | `1.seconds` | Leader-lock acquisition timeout |

## Failure Semantics

- Handler throws → `attempts` already incremented at claim time → status transitions:
  - `attempts >= maxAttempts` → `FAILED`, `lastError` recorded (no further claims)
  - else → `PENDING`, `claimedBy=null`, `claimExpiresAt=null` (re-claimable next batch)
- Leader pod dies mid-handle → `claimExpiresAt` passes → next leader reclaims (at-least-once)
- `lockName` collisions across environments cause silent skip — namespace appropriately

## Migration Tips

- **From cron-based pollers**: replace `@Scheduled` + `synchronized` with `WebhookPoller.start(scope)`. The poller already serializes via leader election.
- **From SQS/Kafka**: model the `eventId` as the dedup key. Use a unique index on `eventId` (auto-created by `WebhookPoller`).
- **DLQ replacement**: query `status = "FAILED"` + `lastError` for postmortem. Reset to `PENDING` to retry manually.

## Dependency

```kotlin
dependencies {
    implementation(project(":leader-mongodb"))
    implementation(project(":examples:webhook-poller"))
}
```

## Testing

```bash
./gradlew :examples:webhook-poller:test
```

Uses Testcontainers MongoDB — Docker daemon required.
