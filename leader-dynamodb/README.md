# bluetape4k-leader-dynamodb

[한국어](README.ko.md)

Preview DynamoDB-backed leader election using conditional writes and logical TTL.

## Behavior

- Table lifecycle is caller-owned.
- The table must use a string partition key named `lockName`.
- Lock rows store `ownerId`, `auditLeaderId`, `nodeId`, `leaseExpiry` in epoch milliseconds, and `ttl` in epoch seconds.
- Acquire uses `PutItem` with a condition that succeeds only when the row does not exist or the logical lease has expired.
- `ttl` is cleanup metadata only. Correctness depends on `leaseExpiry`, not on DynamoDB TTL deletion timing.
- Normal lock contention returns `null`; AWS SDK/client failures are propagated.
- `minLeaseTime` retains the row until the remaining minimum lease expires instead of blocking the caller.
- Single-leader `autoExtend` renews the lease while the action is running. Group electors require explicit `LockExtender` calls.

## Implementations

| Class | Interface | Description |
|---|---|---|
| `DynamoDbLeaderElector` | `LeaderElector` + async facade | Blocking and `CompletableFuture` single-leader election |
| `DynamoDbLeaderGroupElector` | `LeaderGroupElector` | Blocking slot-based multi-leader election |
| `DynamoDbVirtualThreadLeaderElector` | `VirtualThreadLeaderElector` | Virtual-thread single-leader adapter over the blocking elector |
| `DynamoDbVirtualThreadLeaderGroupElector` | `VirtualThreadLeaderGroupElector` | Virtual-thread multi-leader adapter over the blocking group elector |
| `DynamoDbSuspendLeaderElector` | `SuspendLeaderElector` | Coroutine single-leader election with `DynamoDbAsyncClient` |
| `DynamoDbSuspendLeaderGroupElector` | `SuspendLeaderGroupElector` | Coroutine slot-based multi-leader election |

## Table

```text
partition key: lockName (S)
attributes:
  ownerId       S
  auditLeaderId S
  nodeId        S
  leaseExpiry   N  epoch milliseconds
  ttl           N  epoch seconds, DynamoDB TTL attribute
```

Recommended table setup:

```bash
aws dynamodb create-table \
  --table-name bluetape4k_leader_locks \
  --attribute-definitions AttributeName=lockName,AttributeType=S \
  --key-schema AttributeName=lockName,KeyType=HASH \
  --billing-mode PAY_PER_REQUEST

aws dynamodb update-time-to-live \
  --table-name bluetape4k_leader_locks \
  --time-to-live-specification Enabled=true,AttributeName=ttl
```

## Usage

```kotlin
import io.bluetape4k.leader.dynamodb.DynamoDbLeaderElector
import software.amazon.awssdk.services.dynamodb.DynamoDbClient

val client = DynamoDbClient.create()
val elector = DynamoDbLeaderElector(client)

val result = elector.runIfLeader("daily-report") {
    generateReport()
}
// result is generateReport() on the leader, or null on contention
```

Group election:

```kotlin
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.dynamodb.DynamoDbLeaderGroupElectionOptions
import io.bluetape4k.leader.dynamodb.DynamoDbLeaderGroupElector

val group = DynamoDbLeaderGroupElector(
    client,
    DynamoDbLeaderGroupElectionOptions(
        leaderGroupOptions = LeaderGroupElectionOptions(maxLeaders = 3),
    ),
)

group.runIfLeader("parallel-batch") {
    processChunk()
}
```

Virtual-thread API:

```kotlin
import io.bluetape4k.leader.dynamodb.DynamoDbVirtualThreadLeaderElector

val virtualElector = DynamoDbVirtualThreadLeaderElector(elector)
val result = virtualElector.runAsyncIfLeader("daily-report") {
    generateReport()
}.await()
```

Coroutine API:

```kotlin
import io.bluetape4k.leader.dynamodb.DynamoDbSuspendLeaderElector
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient

val asyncClient = DynamoDbAsyncClient.create()
val suspendElector = DynamoDbSuspendLeaderElector(asyncClient)

val result = suspendElector.runIfLeader("nightly-cleanup") {
    cleanupExpiredSessions()
}
```

## Spring Boot

Register caller-owned AWS SDK clients. Auto-configuration creates direct electors, virtual-thread adapters, and AOP factories.

```kotlin
@Bean
fun dynamoDbClient(): DynamoDbClient = DynamoDbClient.create()

@Bean
fun dynamoDbAsyncClient(): DynamoDbAsyncClient = DynamoDbAsyncClient.create()
```

```yaml
bluetape4k:
  leader:
    dynamodb:
      table-name: bluetape4k_leader_locks
      key-prefix: leader
      retry-delay: 50ms
      ttl-padding: 60s
      clock-skew-tolerance: 5s
```

## Testing

The module test suite uses DynamoDB Local through Testcontainers:

```bash
./gradlew :bluetape4k-leader-dynamodb:test
```
