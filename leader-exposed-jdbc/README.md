# leader-exposed-jdbc

[한국어](README.ko.md)

JDBC-backed leader election using [Exposed](https://github.com/JetBrains/Exposed) — blocking and async APIs.

Compatible with H2, PostgreSQL, and MySQL 8.

---

## Overview

`leader-exposed-jdbc` implements `leader-core` interfaces using Exposed's JDBC DSL. A single `LeaderLockTable` row (PK = `lockName`) acts as the distributed mutex; lock ownership is tracked by a UUID fencing token.

Lock strategy: `UPDATE WHERE lockedUntil < NOW()` → `INSERT (on PK conflict: skip)` → `SELECT WHERE token = ?`. All three steps run inside one transaction — no Lua, no `SELECT FOR UPDATE`.

Schema is created automatically on first use via `SchemaUtils.createMissingTablesAndColumns`.

## Architecture

```mermaid
classDiagram
    class LeaderElection {
        <<interface>>
    }
    class AsyncLeaderElection {
        <<interface>>
    }
    class LeaderGroupElection {
        <<interface>>
    }
    class VirtualThreadLeaderElection {
        <<interface>>
    }

    class ExposedJdbcLock {
        +tryLock(waitTime, leaseTime) Boolean
        +unlock()
        +isHeldByCurrentInstance() Boolean
        +token: String
    }
    class ExposedJdbcGroupLock {
        +tryLock(waitTime, leaseTime) Boolean
        +unlock()
        +slot: Int
        +token: String
    }

    ExposedJdbcLeaderElection ..|> LeaderElection
    ExposedJdbcLeaderElection ..|> AsyncLeaderElection
    ExposedJdbcLeaderGroupElection ..|> LeaderGroupElection
    ExposedJdbcVirtualThreadLeaderElection ..|> VirtualThreadLeaderElection

    ExposedJdbcLeaderElection --> ExposedJdbcLock
    ExposedJdbcLeaderGroupElection --> ExposedJdbcGroupLock
    ExposedJdbcVirtualThreadLeaderElection --> ExposedJdbcLeaderElection
```

## Implementations

| Class | Interface | Description |
|-------|-----------|-------------|
| `ExposedJdbcLeaderElection` | `LeaderElection` + `AsyncLeaderElection` | Blocking / CompletableFuture single-leader |
| `ExposedJdbcLeaderGroupElection` | `LeaderGroupElection` | Blocking multi-leader (slot semaphore) |
| `ExposedJdbcVirtualThreadLeaderElection` | `VirtualThreadLeaderElection` | Virtual-thread single-leader |

## Usage

### Setup

```kotlin
val db = Database.connect(hikariDataSource)
```

Schema tables are created automatically on first election call.

### Blocking single-leader

```kotlin
val election = ExposedJdbcLeaderElection(db)

val result = election.runIfLeader("daily-report") {
    generateReport()
}
// result == report on leader node, null on others
```

### Async single-leader (CompletableFuture)

```kotlin
val election = ExposedJdbcLeaderElection(db)

val future: CompletableFuture<Report?> = election.runAsyncIfLeader(
    lockName = "daily-report",
    executor = executor,
    action = { generateReportAsync() }   // returns CompletableFuture<Report>
)
```

### Blocking multi-leader group

```kotlin
val options = ExposedJdbcLeaderGroupElectionOptions(
    leaderGroupOptions = LeaderGroupElectionOptions(maxLeaders = 3)
)
val election = ExposedJdbcLeaderGroupElection(db, options)

val result = election.runIfLeader("parallel-batch") {
    processChunk()
}
// Up to 3 nodes run concurrently; others return null
```

### Virtual-thread single-leader

```kotlin
val election = ExposedJdbcVirtualThreadLeaderElection(db)

val result = election.runInVirtualThread("nightly-sync") {
    syncData()
}
```

### Custom options

```kotlin
val options = ExposedJdbcLeaderElectionOptions(
    leaderOptions = LeaderElectionOptions(
        waitTime = Duration.ofSeconds(5),
        leaseTime = Duration.ofMinutes(1)
    ),
    retryStrategy = RetryStrategy.Jitter(baseDelayMs = 50),
    recordHistory = true,
    lockOwner = "node-1"
)
val election = ExposedJdbcLeaderElection(db, options)
```

## Lock Internals

`ExposedJdbcLock` uses an **UPDATE+INSERT+SELECT** pattern inside a single transaction:

1. **UPDATE** `LeaderLockTable SET token=?, lockedUntil=? WHERE lockName=? AND lockedUntil < NOW()` — takes over an expired lock
2. **INSERT** `LeaderLockTable (lockName, token, lockedUntil, ...)` — creates a new lock if no row exists (PK conflict on contention → silently skipped)
3. **SELECT** `WHERE lockName=? AND token=?` — confirms ownership

This pattern works on all supported databases without database-specific syntax.

## Retry Strategy

```kotlin
sealed class RetryStrategy {
    // Full jitter: delay in [1ms, min(baseDelayMs, remaining))
    data class Jitter(val baseDelayMs: Long = 50L) : RetryStrategy()

    // Exponential backoff capped at maxDelayMs
    data class Exponential(val baseDelayMs: Long = 50L, val maxDelayMs: Long = 5_000L) : RetryStrategy()

    // Fixed interval
    data class Fixed(val fixedMs: Long = 50L) : RetryStrategy()
}
```

Default is `Jitter(50ms)` — suitable for most OLTP workloads.

## History Recording

When `recordHistory = true` (default: `false`), each election attempt writes to `LeaderLockHistoryTable`:

| Status | When |
|--------|------|
| `ACQUIRED` | Lock obtained |
| `COMPLETED` | Action returned normally |
| `FAILED` | Action threw an exception |

History is best-effort — recording failures do not affect lock semantics.

## Database Compatibility

| Database | Tested version |
|----------|---------------|
| H2 | 2.x (in-memory, for tests) |
| PostgreSQL | 14+ |
| MySQL | 8.0+ |

## Dependency

```kotlin
// build.gradle.kts
implementation("io.github.bluetape4k.leader:leader-exposed-jdbc:0.1.0-SNAPSHOT")

// Exposed + JDBC driver must be on the classpath
implementation("org.jetbrains.exposed:exposed-jdbc:1.2.0")
implementation("com.zaxxer:HikariCP:6.x.x")
implementation("org.postgresql:postgresql:42.x.x")  // or mysql-connector-j, etc.
```
