# bluetape4k-leader-etcd

[한국어](./README.ko.md) | English

etcd v3 backend for `bluetape4k-leader`. It uses the jetcd Lock service with
etcd leases, so services that already operate an etcd cluster can elect one
active worker or a bounded group of active workers without adding Redis,
MongoDB, ZooKeeper, or Kubernetes Lease.

## Core Features

- Blocking and async `LeaderElector` implementation
- Coroutine-native `SuspendLeaderElector` implementation
- Virtual-thread adapter over the blocking elector
- Blocking and coroutine `LeaderGroupElector` implementations using per-slot
  jetcd Lock keys
- Watch-backed `LeaderElectionEventPublisher` for ownership key create/delete
  events
- jetcd Lock ownership keys stored as backend tokens for owner-conditional release
- Lease keepalive through the existing `LockExtender` and watchdog contract
- Caller-owned jetcd `Client`; endpoints, TLS, authentication, and lifecycle stay outside the elector
- EtcdServer-backed integration tests using `bluetape4k-testcontainers`
- Spring Boot auto-configuration when `leader-spring-boot` sees a caller-owned jetcd `Client` bean

## Usage

```kotlin
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.etcd.EtcdLeaderElectionOptions
import io.bluetape4k.leader.etcd.EtcdLeaderElector
import io.etcd.jetcd.Client
import kotlin.time.Duration.Companion.seconds

val client = Client.builder()
    .endpoints("http://localhost:2379")
    .build()

val elector = EtcdLeaderElector(
    client = client,
    options = EtcdLeaderElectionOptions(
        keyPrefix = "/apps/orders/leader",
        leaderOptions = LeaderElectionOptions(
            nodeId = "worker-0",
            waitTime = 2.seconds,
            leaseTime = 30.seconds,
            autoExtend = true,
        ),
    ),
)

elector.runIfLeader("daily-report") {
    generateReport()
}
```

Coroutine usage:

```kotlin
import io.bluetape4k.leader.etcd.EtcdSuspendLeaderElector

val suspendElector = EtcdSuspendLeaderElector(client)

suspendElector.runIfLeader("nightly-sync") {
    syncData()
}
```

Virtual-thread usage:

```kotlin
import io.bluetape4k.leader.etcd.EtcdLeaderElector
import io.bluetape4k.leader.etcd.EtcdVirtualThreadLeaderElector

val virtualElector = EtcdVirtualThreadLeaderElector(EtcdLeaderElector(client))

val future = virtualElector.runAsyncIfLeader("webhook-poller") {
    pollWebhooks()
}
```

Client extension:

```kotlin
import io.bluetape4k.leader.etcd.runIfLeader

client.runIfLeader("cache-warmer") {
    warmCache()
}
```

Group usage:

```kotlin
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.etcd.EtcdLeaderGroupElectionOptions
import io.bluetape4k.leader.etcd.EtcdLeaderGroupElector

val groupElector = EtcdLeaderGroupElector(
    client = client,
    options = EtcdLeaderGroupElectionOptions(
        keyPrefix = "/apps/orders/leader",
        leaderGroupOptions = LeaderGroupElectionOptions(maxLeaders = 3),
    ),
)

groupElector.runIfLeader("partition-worker") {
    processPartition()
}
```

Watch-backed event stream:

```kotlin
import io.bluetape4k.leader.etcd.EtcdLeaderElectionEventPublisher
import kotlinx.coroutines.flow.collect

val publisher = EtcdLeaderElectionEventPublisher(
    client = client,
    keyPrefix = "/apps/orders/leader",
)

publisher.events.collect { event ->
    recordLeaderEvent(event)
}
```

## Configuration

| Option | Type | Default | Description |
| --- | --- | --- | --- |
| `keyPrefix` | `String` | `/bluetape4k/leader` | Absolute etcd key prefix for lock keys |
| `retryDelay` | `Duration` | `50.milliseconds` | Cleanup wait floor: unlock and lease revoke block for `max(waitTime, retryDelay)`. Also reserved for retrying APIs outside jetcd queued locks. |
| `leaderOptions.waitTime` | `Duration` | `5.seconds` | Maximum time budget for lease grant, lock acquisition, and cleanup (unlock and lease revoke block for `max(waitTime, retryDelay)`) |
| `leaderOptions.leaseTime` | `Duration` | `60.seconds` | etcd lease TTL |
| `leaderOptions.nodeId` | `String` | process-level default | Audit node id shared with core contracts |
| `leaderOptions.minLeaseTime` | `Duration` | `0.seconds` | Minimum leadership hold time after quick actions |
| `leaderOptions.autoExtend` | `Boolean` | `false` | Keeps the active etcd lease alive while the action runs |
| `leaderGroupOptions.maxLeaders` | `Int` | `2` | Maximum number of concurrent group leaders |
| `leaderGroupOptions.waitTime` | `Duration` | `5.seconds` | Maximum time budget for group slot acquisition |
| `leaderGroupOptions.leaseTime` | `Duration` | `60.seconds` | etcd lease TTL for group slots |
| `leaderGroupOptions.minLeaseTime` | `Duration` | `0.seconds` | Minimum group-slot hold time after quick actions |

`lockName` is percent-encoded into an etcd path segment. The raw name may
contain Unicode, slash, or colon characters; the encoded key remains under
`keyPrefix`.

Group election uses one etcd Lock key per slot:
`{keyPrefix}/group/{encodedLockName}/slot-{n}`. Slot acquisition starts at a
random slot and traverses the remaining slots with a bounded per-slot wait so a
single contended slot cannot consume the full group acquisition budget.

`EtcdLeaderElectionEventPublisher` watches the configured `keyPrefix` and emits
`Elected` for Lock ownership key `PUT` events and `Revoked` for `DELETE` events.
It revalidates the current owner so queued jetcd Lock contenders are not reported
as active leaders. It does not emit `Skipped`, because skipped attempts are
local acquisition outcomes rather than etcd state changes. Closing the publisher
closes only the watch; the caller-owned jetcd `Client` remains open.

## Dependency

Gradle (Kotlin DSL):

```kotlin
dependencies {
    implementation("io.github.bluetape4k.leader:bluetape4k-leader-etcd:$bluetape4kLeaderVersion")
}
```

The module exposes jetcd Core as an API dependency because constructors accept
`io.etcd.jetcd.Client`.

## Spring Boot

Add both `leader-etcd` and `leader-spring-boot`, then register a jetcd `Client`
bean. Spring auto-configuration creates the blocking, coroutine, and group
electors from that caller-owned client and leaves endpoints, TLS, authentication,
and lifecycle management outside the library.

```yaml
bluetape4k:
  leader:
    etcd:
      key-prefix: /apps/orders/leader
```

`EtcdLeaderElectionEventPublisher` is not auto-created because constructing it
starts a live watch. Create and close the publisher explicitly when the
application needs backend ownership events.

## Testing

```bash
./gradlew :bluetape4k-leader-etcd:test
```

Integration tests start a real etcd container through
`EtcdServer.Launcher.etcd`; Docker is required.

## License

MIT License
