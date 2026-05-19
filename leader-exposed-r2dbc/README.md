# leader-exposed-r2dbc

[한국어](README.ko.md)

Coroutine-native leader election backed by a relational database — using [Exposed R2DBC](https://github.com/JetBrains/Exposed) for non-blocking reactive SQL.

---

## Overview

`leader-exposed-r2dbc` implements `leader-core` interfaces using Exposed's R2DBC support. All database operations are fully non-blocking (`suspendTransaction`), making it suitable for coroutine-based services without a dedicated connection thread pool per request.

Lock strategy: `UPDATE WHERE lockedUntil < NOW()` + `INSERT IGNORE` in a single transaction — no Redis, no external broker required. Works with H2 (in-memory), PostgreSQL, and MySQL 8.

## Architecture

![Architecture 1](../docs/images/readme-diagrams/leader-exposed-r2dbc-diagram-01.svg)

## Implementations

| Class | Interface | Description |
|-------|-----------|-------------|
| `ExposedR2dbcSuspendLeaderElector` | `SuspendLeaderElector` | Coroutine single-leader via `ExposedR2dbcLock` |
| `ExposedR2dbcSuspendLeaderGroupElector` | `SuspendLeaderGroupElector` | Coroutine multi-leader via `ExposedR2dbcGroupLock` |
| `ExposedR2DbcSuspendLeaderElectorFactory` | `SuspendLeaderElectorFactory` | Factory: creates `ExposedR2dbcSuspendLeaderElector` per call |
| `ExposedR2DbcSuspendLeaderGroupElectorFactory` | `SuspendLeaderGroupElectorFactory` | Factory: creates `ExposedR2dbcSuspendLeaderGroupElector` per call |

## Usage

### Connect to a database

```kotlin
// H2 in-memory (development / testing)
val db = R2dbcDatabase.connect("r2dbc:h2:mem:///leader;MODE=MySQL;DB_CLOSE_DELAY=-1")

// PostgreSQL
val db = R2dbcDatabase.connect("r2dbc:postgresql://user:pass@localhost:5432/mydb")

// MySQL 8
val db = R2dbcDatabase.connect("r2dbc:mysql://user:pass@localhost:3306/mydb")
```

Schema is created automatically on the first call to `runIfLeader`.

### Coroutine single-leader

```kotlin
val election = ExposedR2dbcSuspendLeaderElector(db)

val result = election.runIfLeader("daily-report") {
    delay(100)
    generateReport()
}
// result == report on the elected node, null on others
```

### Coroutine multi-leader group

```kotlin
val options = ExposedR2dbcLeaderGroupElectionOptions(
    leaderGroupOptions = LeaderGroupElectionOptions(maxLeaders = 3)
)
val election = ExposedR2dbcSuspendLeaderGroupElector(db, options)

coroutineScope {
    val jobs = (1..10).map {
        async {
            election.runIfLeader("parallel-batch") {
                processChunk(it)
            }
        }
    }
    jobs.awaitAll()
    // At most 3 run concurrently; others return null
}
```

### Extension functions

```kotlin
// Single-leader shorthand
val report = db.suspendRunIfLeader("daily-report") {
    generateReport()
} ?: return  // skip if not elected

// Multi-leader shorthand
val result = db.suspendRunIfLeaderGroup("worker-pool") {
    processChunk()
}
```

### Custom options

```kotlin
val options = ExposedR2dbcLeaderElectionOptions(
    leaderOptions = LeaderElectionOptions(
        waitTime = 3.seconds,
        leaseTime = 30.seconds,
    ),
    retryStrategy = RetryStrategy.Jitter(baseDelayMs = 50L),
    recordHistory = true,
    lockOwner = "worker-1",
)
val election = ExposedR2dbcSuspendLeaderElector(db, options)
```

### Using SPI factories

```kotlin
val factory: SuspendLeaderElectorFactory = ExposedR2DbcSuspendLeaderElectorFactory(db)

coroutineScope {
    val elector = factory.create(LeaderElectionOptions.Default)
    val result = elector.runIfLeader("daily-job") { generateReport() }
}
```

```kotlin
val groupFactory: SuspendLeaderGroupElectorFactory = ExposedR2DbcSuspendLeaderGroupElectorFactory(db)

coroutineScope {
    val elector = groupFactory.create(LeaderGroupElectionOptions(maxLeaders = 3))
    val result = elector.runIfLeader("parallel-batch") { processChunk() }
}
```

## Lock Strategy

### Single-leader (`ExposedR2dbcLock`)

One transaction per attempt:

1. **UPDATE** — claim an expired row: `UPDATE SET token=?, lockedUntil=? WHERE lockName=? AND lockedUntil < NOW()`
2. **INSERT IGNORE** — insert a new row if none exists (no-op on conflict)
3. **SELECT** — verify this instance's token is in the row

Unlock removes the row only if the token matches — zombie unlock prevention.

### Multi-leader (`ExposedR2dbcGroupLock`)

Same strategy, keyed on `(lockName, slot)` composite PK. Each slot is an independent lock; `maxLeaders` determines the number of slots.

### Database compatibility

| Database | INSERT strategy | Notes |
|----------|-----------------|-------|
| PostgreSQL | `INSERT ... ON CONFLICT DO NOTHING` | Full support |
| MySQL 8 | `INSERT IGNORE INTO` | Full support |
| H2 | `INSERT IGNORE INTO` (MySQL mode) | URL must include `MODE=MySQL` |

## Retry Strategies

| Strategy | Description |
|----------|-------------|
| `RetryStrategy.Jitter(baseDelayMs)` | AWS full-jitter: `random(0, baseDelay * attempt)` |
| `RetryStrategy.Fixed(fixedMs)` | Fixed delay between attempts |
| `RetryStrategy.Exponential(baseMs, factor)` | Exponential backoff with optional jitter |

## Schema

Tables are defined in `leader-exposed-core` and created via `ExposedR2dbcSchemaInitializer.ensureSchema(db)` (called automatically):

| Table | Purpose |
|-------|---------|
| `leader_lock` | Single-leader lock rows |
| `leader_group_lock` | Multi-leader slot rows |
| `leader_lock_history` | Audit log (only when `recordHistory = true`) |

## Dependency

```kotlin
// build.gradle.kts
implementation("io.github.bluetape4k.leader:bluetape4k-leader-exposed-r2dbc:0.1.0-SNAPSHOT")

// R2DBC driver — choose one:
runtimeOnly("io.r2dbc:r2dbc-h2:1.x")
runtimeOnly("org.postgresql:r2dbc-postgresql:1.x")
runtimeOnly("io.asyncer:r2dbc-mysql:1.x")
```

> H2 requires `MODE=MySQL` in the R2DBC URL for `INSERT IGNORE` support.
