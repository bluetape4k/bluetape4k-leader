# leader-exposed-core

English | [한국어](README.ko.md)

Shared DB schema and table definitions for RDBMS-based distributed leader election backends.

This module is a dependency for `leader-exposed-jdbc` and `leader-exposed-r2dbc`.
It contains no JDBC/R2DBC drivers — only Exposed table definitions and constants.

## Tables

| Table | Object | Purpose |
|-------|--------|---------|
| `bluetape4k_leader_locks` | `LeaderLockTable` | Single-leader election lock |
| `bluetape4k_leader_group_locks` | `LeaderGroupLockTable` | Multi-leader (semaphore) election lock |
| `bluetape4k_leader_lock_history` | `LeaderLockHistoryTable` | Lock acquisition history |

### LeaderLockTable

Primary key: `lock_name` (VARCHAR 255)

| Column | Type | Notes |
|--------|------|-------|
| `lock_name` | VARCHAR(255) | PK, validated by `validateLockName()` |
| `lock_owner` | VARCHAR(255) | Nullable, instance identifier |
| `token` | VARCHAR(36) | UUID fencing token |
| `locked_at` | TIMESTAMP | Acquisition time |
| `locked_until` | TIMESTAMP | TTL expiry; use `locked_until < NOW()` to detect expired locks |

### LeaderGroupLockTable

Composite primary key: `(lock_name, slot)`

| Column | Type | Notes |
|--------|------|-------|
| `lock_name` | VARCHAR(255) | Part of PK |
| `slot` | INTEGER | Part of PK, range `[0, maxLeaders)` |
| `lock_owner` | VARCHAR(255) | Nullable |
| `token` | VARCHAR(36) | UUID fencing token |
| `locked_at` | TIMESTAMP | Acquisition time |
| `locked_until` | TIMESTAMP | TTL expiry |

### LeaderLockHistoryTable

Primary key: `id` (auto-increment BIGINT)

| Column | Type | Notes |
|--------|------|-------|
| `id` | BIGINT | Auto-increment |
| `lock_name` | VARCHAR(255) | Lock name |
| `lock_owner` | VARCHAR(255) | Nullable |
| `token` | VARCHAR(36) | UUID |
| `slot` | INTEGER | Nullable; only for group locks |
| `locked_until` | TIMESTAMP | TTL at acquisition time |
| `status` | VARCHAR(20) | `ACQUIRED`, `COMPLETED`, `FAILED`, `EXPIRED` |
| `started_at` | TIMESTAMP | Lock acquisition timestamp (indexed) |
| `finished_at` | TIMESTAMP | Nullable; null for ACQUIRED status |
| `duration_ms` | BIGINT | Nullable; null for ACQUIRED status |

Indexes: `idx_history_lock_started (lock_name, started_at)`, `idx_history_token (token)`

## Schema Helper

```kotlin
// Create all tables
SchemaUtils.createMissingTablesAndColumns(*ExposedLeaderSchema.allTables)

// Drop all tables
SchemaUtils.drop(*ExposedLeaderSchema.allTables)
```

`ExposedLeaderSchema.allTables` is `Array<Table>` for zero-copy spread to Exposed vararg APIs.

## Lock Name Validation

```kotlin
import io.bluetape4k.leader.validateLockName

validateLockName("my-job")           // OK
validateLockName("batch:worker:0")   // OK
validateLockName("invalid name")     // throws IllegalArgumentException
validateLockName("-bad-start")       // throws IllegalArgumentException
```

Rules:
- Not blank; max 255 characters
- Must match `^[a-zA-Z0-9][a-zA-Z0-9_\-:]{0,254}$`
- Must start with alphanumeric character

## Dependencies

```kotlin
// Production
api(project(":leader-core"))
api(libs.exposed.core)
api(libs.exposed.java.time)
compileOnly(libs.exposed.dao)

// Test only
testImplementation(libs.exposed.jdbc.tests) // TestDB, withTables
testImplementation(libs.exposed.jdbc)
testImplementation(libs.hikaricp)
```
