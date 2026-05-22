# bluetape4k-leader-etcd

한국어 | [English](./README.md)

`bluetape4k-leader` 의 etcd v3 백엔드입니다. jetcd Lock service 와 etcd
lease 를 사용하므로 이미 etcd cluster 를 운영하는 서비스는 Redis, MongoDB,
ZooKeeper, Kubernetes Lease 를 추가하지 않고 단일 active worker 를 선출할 수
있습니다.

## Core Features

- Blocking / async `LeaderElector` 구현
- Coroutine-native `SuspendLeaderElector` 구현
- Blocking elector 위의 virtual-thread adapter
- jetcd Lock ownership key 를 backend token 으로 보관해 owner-conditional release 수행
- 기존 `LockExtender` 와 watchdog 계약을 통한 lease keepalive
- jetcd `Client` 는 caller-owned; endpoint, TLS, 인증, lifecycle 은 elector 밖에서 관리
- `bluetape4k-testcontainers` 의 EtcdServer 기반 통합 테스트

Group election, state watch API, Spring Boot auto-configuration 은 이후 issue
slice 에서 구현합니다.

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

Coroutine 사용:

```kotlin
import io.bluetape4k.leader.etcd.EtcdSuspendLeaderElector

val suspendElector = EtcdSuspendLeaderElector(client)

suspendElector.runIfLeader("nightly-sync") {
    syncData()
}
```

Virtual thread 사용:

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

## Configuration

| 옵션 | 타입 | 기본값 | 설명 |
| --- | --- | --- | --- |
| `keyPrefix` | `String` | `/bluetape4k/leader` | lock key 를 저장할 absolute etcd key prefix |
| `retryDelay` | `Duration` | `50.milliseconds` | jetcd queued lock 을 직접 쓰지 않는 retry API 용 예약 옵션 |
| `leaderOptions.waitTime` | `Duration` | `5.seconds` | lease grant 와 lock 획득 전체 최대 대기 시간 |
| `leaderOptions.leaseTime` | `Duration` | `60.seconds` | etcd lease TTL |
| `leaderOptions.nodeId` | `String` | process-level default | core 계약과 공유하는 audit node id |
| `leaderOptions.minLeaseTime` | `Duration` | `0.seconds` | 빠른 action 뒤 최소 leadership 유지 시간 |
| `leaderOptions.autoExtend` | `Boolean` | `false` | action 실행 중 active etcd lease 자동 keepalive |

`lockName` 은 etcd path segment 로 percent-encoding 됩니다. 원본 이름에는
Unicode, slash, colon 이 들어갈 수 있으며 encoded key 는 항상 `keyPrefix` 아래에
남습니다.

## Dependency

Gradle (Kotlin DSL):

```kotlin
dependencies {
    implementation("io.github.bluetape4k.leader:bluetape4k-leader-etcd:$bluetape4kLeaderVersion")
}
```

생성자가 `io.etcd.jetcd.Client` 를 받으므로 jetcd Core 는 API dependency 로
노출됩니다.

## Testing

```bash
./gradlew :bluetape4k-leader-etcd:test
```

통합 테스트는 `EtcdServer.Launcher.etcd` 로 실제 etcd container 를 시작합니다.
Docker 가 필요합니다.

## License

MIT License
