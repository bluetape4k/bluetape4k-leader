# examples-batch-scheduler

[한국어](./README.ko.md) | English

Distributed batch scheduler example using Lettuce-Redis backend. Demonstrates safe single execution of a periodic batch job (e.g. nightly settlement) across multiple deployed instances.

## Architecture

```mermaid
sequenceDiagram
    participant Cron
    participant N1 as Node-1
    participant N2 as Node-2
    participant N3 as Node-3
    participant R as Redis

    Cron->>N1: 02:00 trigger
    Cron->>N2: 02:00 trigger
    Cron->>N3: 02:00 trigger
    par
        N1->>R: SET nightly-settlement (NX EX 30s)
    and
        N2->>R: SET nightly-settlement (NX EX 30s)
    and
        N3->>R: SET nightly-settlement (NX EX 30s)
    end
    R-->>N1: OK (lock acquired)
    R-->>N2: nil (skip)
    R-->>N3: nil (skip)
    N1->>N1: run settlement job
    N1->>R: DEL nightly-settlement
```

## Core Features

- Single-execution guarantee for periodic batch jobs across N replicas
- Automatic skip on contention (no exception thrown — `null` return like ShedLock)
- Lock release on success, failure, or exception
- Lease TTL prevents lock leak if leader crashes mid-job

## Usage Example

```kotlin
val redisConnection: StatefulRedisConnection<String, String> = client.connect(StringCodec.UTF8)

val scheduler = BatchScheduler(
    nodeId = "node-${System.getenv("HOSTNAME")}",
    connection = redisConnection,
    lockName = "nightly-settlement",
    waitTime = 2.seconds,
    leaseTime = 30.seconds,
)

// Called by cron / Spring @Scheduled / Quartz
val result: Unit? = scheduler.run {
    settlementService.processYesterday()
}

if (result == null) {
    log.info { "Another instance is processing — skipping" }
}
```

## Demo

```bash
./gradlew :examples:batch-scheduler:run
```

Or directly: run `BatchSchedulerDemo.main()` from your IDE. Spawns 3 simulated instances; only 1 runs the job.

## Configuration Options

| Parameter | Default | Description |
|-----------|---------|-------------|
| `nodeId` | required | Unique identifier per instance — used in logs |
| `lockName` | required | Distributed lock key (same across all instances of the job) |
| `waitTime` | `2.seconds` | Time to wait for the lock before giving up |
| `leaseTime` | `30.seconds` | Lock TTL — prevents leak on crash; should exceed expected job duration |

## Dependency

```kotlin
dependencies {
    implementation(project(":leader-redis-lettuce"))
    implementation(project(":examples:batch-scheduler"))
}
```

## Testing

```bash
./gradlew :examples:batch-scheduler:test
```

Tests use Testcontainers Redis singleton — Docker daemon required.
