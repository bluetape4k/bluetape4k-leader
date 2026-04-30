# leader-core

[한국어](README.ko.md)

Core interfaces and local in-process implementations for `bluetape4k-leader`.

---

## Overview

`leader-core` defines the contracts for all leader election backends and provides local (in-process) implementations that need no external infrastructure. Use local implementations in single-instance deployments or tests.

## Architecture

```mermaid
classDiagram
    class AsyncLeaderElection {
        <<interface>>
        +runAsyncIfLeader(lockName, action) CompletableFuture~T?~
    }
    class LeaderElection {
        <<interface>>
        +runIfLeader(lockName, action) T?
    }
    class VirtualThreadLeaderElection {
        <<interface>>
        +runIfLeader(lockName, action) T?
    }
    class SuspendLeaderElection {
        <<interface>>
        +runIfLeader(lockName, action) T?
    }
    class AsyncLeaderGroupElection {
        <<interface>>
        +runAsyncIfLeader(lockName, action) CompletableFuture~T?~
        +state(lockName) LeaderGroupState
        +activeCount(lockName) Int
        +availableSlots(lockName) Int
    }
    class LeaderGroupElection {
        <<interface>>
        +runIfLeader(lockName, action) T?
    }
    class SuspendLeaderGroupElection {
        <<interface>>
        +runIfLeader(lockName, action) T?
    }

    LeaderElection --|> AsyncLeaderElection
    VirtualThreadLeaderElection --|> AsyncLeaderElection
    LeaderGroupElection --|> AsyncLeaderGroupElection
    SuspendLeaderGroupElection --|> AsyncLeaderGroupElection
```

## API Contract

### `runIfLeader(lockName, action): T?`

- Acquires the named lock (or semaphore slot for group elections)
- If acquired: executes `action` and returns its result
- If not acquired within `waitTime`: returns **`null`** (never throws on contention)
- Exceptions from `action` are propagated to the caller
- Lock is released after `action` completes (or on exception)

### Options

```kotlin
LeaderElectionOptions(
    waitTime: Duration = Duration.ofSeconds(5),   // max wait for lock acquisition
    leaseTime: Duration = Duration.ofSeconds(60)  // max lock hold time
)

LeaderGroupElectionOptions(
    maxLeaders: Int = 2,                          // max concurrent leaders
    waitTime: Duration = Duration.ofSeconds(5),
    leaseTime: Duration = Duration.ofSeconds(60)
)
```

## Sequence Diagrams

### Single-leader: lock acquire/release

```mermaid
sequenceDiagram
    participant Caller
    participant LeaderElection
    participant LockStore

    Caller->>LeaderElection: runIfLeader(lockName, action)
    LeaderElection->>LockStore: tryLock(lockName, waitTime, leaseTime)

    alt lock acquired
        LockStore-->>LeaderElection: true
        LeaderElection->>Caller: action()
        Caller-->>LeaderElection: result
        LeaderElection->>LockStore: unlock(lockName)
        LeaderElection-->>Caller: result (T)
    else not acquired within waitTime
        LockStore-->>LeaderElection: false
        LeaderElection-->>Caller: null
    end
```

### Multi-leader group: slot-based semaphore (maxLeaders = N)

```mermaid
sequenceDiagram
    participant Caller
    participant GroupElection
    participant LockStore

    Caller->>GroupElection: runIfLeader(lockName, action)
    loop slot = 0..N-1
        GroupElection->>LockStore: tryLock(lockName:slot:i, ...)
        alt slot acquired
            LockStore-->>GroupElection: true
            Note over GroupElection: acquired slot i
            GroupElection->>Caller: action()
            Caller-->>GroupElection: result
            GroupElection->>LockStore: unlock(lockName:slot:i)
            GroupElection-->>Caller: result (T)
        else slot busy
            LockStore-->>GroupElection: false
            Note over GroupElection: try next slot
        end
    end
    Note over GroupElection: all slots busy → return null
```

## Local Implementations

All local implementations use JVM primitives (`ReentrantLock`, `Semaphore`) — no external dependencies.

| Class | Interface | Description |
|-------|-----------|-------------|
| `LocalLeaderElection` | `LeaderElection` | Blocking, `ReentrantLock`-based |
| `LocalAsyncLeaderElection` | `AsyncLeaderElection` | `CompletableFuture` on thread pool |
| `LocalVirtualThreadLeaderElection` | `VirtualThreadLeaderElection` | Virtual thread per election |
| `LocalSuspendLeaderElection` | `SuspendLeaderElection` | Coroutine with `Mutex` |
| `LocalLeaderGroupElection` | `LeaderGroupElection` | `Semaphore`-based multi-leader |
| `LocalSuspendLeaderGroupElection` | `SuspendLeaderGroupElection` | Coroutine `Semaphore` |
| `LocalStrategicLeaderElection` | `StrategicLeaderElection` | Strategy-based blocking election |
| `LocalStrategicSuspendLeaderElection` | `StrategicSuspendLeaderElection` | Strategy-based coroutine election |

## Strategic Election

### Overview

Strategic election separates the **nomination phase** (candidate registration) from the **decision phase** (strategy application), enabling flexible leader selection policies.

```
registerCandidate() → selectLeader(strategy) → 1 winner, rest skipped
```

### Built-in Strategies

| Strategy | Description |
|----------|-------------|
| `FifoElectionStrategy` | Earliest registered candidate wins |
| `RandomElectionStrategy(seed)` | Deterministic random selection (seed required for distributed use) |
| `ScoredElectionStrategy(scorer)` | Highest-scoring candidate wins |

### Built-in Scorers (0–100 normalized)

| Scorer | Description |
|--------|-------------|
| `IdleTimeScorer` | Node idle longest since last completion |
| `SuccessRateScorer` | Highest success-rate node |
| `RecentSuccessScorer` | Most recently succeeded node |
| `WeightedScorer` | Weighted sum of multiple scorers |

### Key Interfaces

```kotlin
interface StrategicLeaderElection {
    val nodeId: String
    fun registerCandidate(lockName: String, info: CandidateInfo, ttl: Duration = Duration.ZERO)
    fun unregisterCandidate(lockName: String, nodeId: String)
    fun listCandidates(lockName: String): List<CandidateInfo>
    fun <T> runIfLeader(lockName: String, strategy: ElectionStrategy, options: LeaderElectionOptions, action: () -> T): T?
}
```

## Usage Examples

### Strategic election — scored idle-time

```kotlin
val election = LocalStrategicLeaderElection("node-1")

election.registerCandidate("batch-job", CandidateInfo("node-1"))
election.registerCandidate("batch-job", CandidateInfo("node-2"))

val result = election.runIfLeader("batch-job", ScoredElectionStrategy(IdleTimeScorer)) {
    processBatch()
}
// Only the node idle longest runs processBatch(); others return null
```

### Strategic election — weighted scorer

```kotlin
val scorer = WeightedScorer(IdleTimeScorer to 0.4, SuccessRateScorer to 0.6)
val strategy = ScoredElectionStrategy(scorer)

val result = election.runIfLeader("weighted-job", strategy) { work() }
```

### Blocking single-leader

```kotlin
val election = LocalLeaderElection()

val result = election.runIfLeader("daily-job") {
    processData()
}
// result == processData() on success, null if lock not acquired
```

### Coroutine suspend single-leader

```kotlin
val election = LocalSuspendLeaderElection()

val result = election.runIfLeader("nightly-sync") {
    syncToRemote()
}
```

### Multi-leader group (semaphore)

```kotlin
val options = LeaderGroupElectionOptions(maxLeaders = 3)
val election = LocalLeaderGroupElection(options)

// Up to 3 concurrent calls can run this action at once
val result = election.runIfLeader("parallel-batch") {
    processChunk()
}

println(election.activeCount("parallel-batch"))   // 0–3
println(election.availableSlots("parallel-batch")) // 3 - activeCount
```

### State inspection

```kotlin
val state: LeaderGroupState = election.state("parallel-batch")
println(state.activeCount)    // current leader count
println(state.maxLeaders)     // maxLeaders from options
```

## Dependency

```kotlin
// build.gradle.kts
implementation("io.github.bluetape4k.leader:leader-core:0.1.0-SNAPSHOT")
```
