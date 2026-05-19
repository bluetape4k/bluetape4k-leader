# leader-mongodb

[한국어](README.ko.md)

MongoDB-backed leader election using `findOneAndUpdate` + TTL index — blocking, async, and coroutine APIs.

---

## Overview

`leader-mongodb` implements `leader-core` interfaces using MongoDB's `findOneAndUpdate` with `upsert=true` as the atomic lock primitive. A TTL index on `expireAt` handles automatic expiry. Lock ownership is tracked by a per-instance UUID token, making it safe across coroutine thread switches.

When `minLeaseTime` is configured, unlock updates `expireAt` to the remaining minimum lease instead of deleting the document, matching ShedLock `lockAtLeastFor` behavior without blocking the caller. With `LeaderElectionOptions(autoExtend = true)`, single-leader electors periodically update `expireAt` only when the stored token still matches the owner.

Lock strategy:
- **Acquire**: `findOneAndUpdate(filter: {_id, expireAt < now}, update: {token, expireAt}, upsert=true, returnDocument=AFTER)` — succeeds if the returned token matches; `E11000` means a live lock exists → retry.
- **Release**: `deleteOne({_id, token})`, or `updateOne({_id, token}, expireAt = now + remainingMinLeaseTime)` when `minLeaseTime` still has time left.

## Architecture

![Architecture 1](../docs/images/readme-diagrams/leader-mongodb-diagram-01.png)

## Implementations

| Class | Interface | Description |
|-------|-----------|-------------|
| `MongoLeaderElector` | `LeaderElector` + `AsyncLeaderElector` | Blocking / async single-leader via `MongoLock` |
| `MongoLeaderGroupElector` | `LeaderGroupElector` | Blocking multi-leader via slot-based `MongoLock` |
| `MongoSuspendLeaderElector` | `SuspendLeaderElector` | Coroutine single-leader via `MongoSuspendLock` |
| `MongoSuspendLeaderGroupElector` | `SuspendLeaderGroupElector` | Coroutine multi-leader via slot-based `MongoSuspendLock` |
| `MongoSuspendLeaderElectorFactory` | `SuspendLeaderElectorFactory` | Factory: creates `MongoSuspendLeaderElector` per call |
| `MongoSuspendLeaderGroupElectorFactory` | `SuspendLeaderGroupElectorFactory` | Factory: creates `MongoSuspendLeaderGroupElector` per call |

## Collections

| Collection | Purpose |
|------------|---------|
| `bluetape4k_leader_locks` | Single-leader lock documents |
| `bluetape4k_leader_group_locks` | Multi-leader slot documents (`lockName:slot:N`) |

TTL index on `expireAt` (expireAfterSeconds=0) is created automatically on first use.

## Usage

### Setup

```kotlin
val mongoClient = MongoClients.create("mongodb://localhost:27017")
val db = mongoClient.getDatabase("mydb")
val lockCollection = db.getCollection("bluetape4k_leader_locks")
```

### Blocking single-leader

```kotlin
val election = MongoLeaderElector(lockCollection)

val result = election.runIfLeader("daily-report") {
    generateReport()
}
// result == report on leader node, null if lock not acquired within waitTime
```

### Blocking multi-leader group

```kotlin
val options = MongoLeaderGroupElectionOptions(
    leaderGroupOptions = LeaderGroupElectionOptions(maxLeaders = 3)
)
val groupCollection = db.getCollection("bluetape4k_leader_group_locks")
val election = MongoLeaderGroupElector(groupCollection, options)

val result = election.runIfLeader("parallel-batch") {
    processChunk()
}
// up to 3 nodes run concurrently, others get null
```

### Async single-leader

```kotlin
val election = MongoLeaderElector(lockCollection)

val future: CompletableFuture<String?> = election.runAsyncIfLeader(
    "async-job",
    VirtualThreadExecutor
) {
    futureOf { doWork() }
}
val result = future.get(5, TimeUnit.SECONDS)
```

### Coroutine single-leader

```kotlin
// MongoSuspendLeaderElector is a suspend factory
val coroutineCollection = coroutineMongoClient.getDatabase("mydb")
    .getCollection<Document>("bluetape4k_leader_locks")
val election = MongoSuspendLeaderElector(coroutineCollection)

val result = election.runIfLeader("nightly-sync") {
    syncData()
}
```

### Coroutine multi-leader group

```kotlin
val syncCollection = db.getCollection("bluetape4k_leader_group_locks")
val coroutineCollection = coroutineDb.getCollection<Document>("bluetape4k_leader_group_locks")
val election = MongoSuspendLeaderGroupElector(syncCollection, coroutineCollection)

val result = election.runIfLeader("task-group") {
    processTask()
}
// up to maxLeaders nodes run concurrently
```

### Custom options

```kotlin
val options = MongoLeaderElectionOptions(
    leaderOptions = LeaderElectionOptions(
        waitTime = 5.seconds,
        leaseTime = 60.seconds,
    ),
    retryDelay = 100.milliseconds,
)
val election = MongoLeaderElector(lockCollection, options)
```

### Using SPI factories

```kotlin
val coroutineCollection = coroutineDb.getCollection<Document>("bluetape4k_leader_locks")
val factory: SuspendLeaderElectorFactory =
    MongoSuspendLeaderElectorFactory(coroutineCollection)

coroutineScope {
    val elector = factory.create(LeaderElectionOptions.Default)
    val result = elector.runIfLeader("daily-job") { doWork() }
}
```

```kotlin
val syncGroupCollection = db.getCollection("bluetape4k_leader_group_locks")
val coroutineGroupCollection = coroutineDb.getCollection<Document>("bluetape4k_leader_group_locks")
val groupFactory: SuspendLeaderGroupElectorFactory =
    MongoSuspendLeaderGroupElectorFactory(syncGroupCollection, coroutineGroupCollection)

coroutineScope {
    val elector = groupFactory.create(LeaderGroupElectionOptions(maxLeaders = 3))
    val result = elector.runIfLeader("parallel-job") { processChunk() }
}
```

## Lock Internals

**`MongoLock`** (blocking, sync driver):

```kotlin
collection.findOneAndUpdate(
    Filters.and(eq("_id", lockKey), lt("expireAt", Date())),
    Updates.combine(set("token", token), set("expireAt", expiry)),
    FindOneAndUpdateOptions().upsert(true).returnDocument(AFTER)
)
// E11000 DuplicateKey → live lock exists → retry with jitter
```

**`MongoSuspendLock`** (coroutine driver):
- Same strategy with `delay()` instead of `Thread.sleep()`
- `currentCoroutineContext().ensureActive()` on each retry → cancellation-safe

**Cancellation safety (coroutine)**:

```kotlin
try {
    return action()
} finally {
    withContext(NonCancellable) {
        lock.unlock()  // protected from cancellation
    }
}
```

## Dual-collection design (`MongoSuspendLeaderGroupElector`)

`activeCount()`, `availableSlots()`, and `state()` are non-suspend interface methods. The coroutine driver's `countDocuments` is `suspend`, so state queries use the **sync driver** and lock operations use the **coroutine driver**:

```kotlin
MongoSuspendLeaderGroupElector(
    groupCollection = db.getCollection("bluetape4k_leader_group_locks"),        // sync — for state()
    coroutineGroupCollection = coroutineDb.getCollection("bluetape4k_leader_group_locks"),  // suspend — for locks
)
```

## Notes

- For single-leader elections, `autoExtend=true` can renew `expireAt` while the action runs. Group elections still require `leaseTime` to cover the expected action duration.
- MongoDB TTL index fires at most every 60 seconds — expired documents may linger briefly.
- `activeCount()` / `availableSlots()` are approximate due to the TTL expiry window.
- Replica Set environments: `WriteConcern.MAJORITY` is recommended for strong consistency.

## Dependency

```kotlin
// build.gradle.kts
implementation("io.github.bluetape4k.leader:bluetape4k-leader-mongodb:0.1.0-SNAPSHOT")

// MongoDB drivers must be on the classpath
implementation("org.mongodb:mongodb-driver-sync:5.x.x")
implementation("org.mongodb:mongodb-driver-kotlin-coroutine:5.x.x")  // for suspend APIs
```
