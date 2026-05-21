# bluetape4k-leader-k8s

한국어 | [English](./README.md)

`bluetape4k-leader` 의 Kubernetes Lease 백엔드입니다. Kubernetes 기본
`coordination.k8s.io/v1` Lease API 를 사용하므로 Redis, MongoDB, ZooKeeper,
커스텀 CRD 없이 Kubernetes 안에서 단일 active worker 를 선출할 수 있습니다.

## Architecture

![leader-k8s architecture diagram](../docs/images/readme-diagrams/leader-k8s-architecture-01.png)

![leader-k8s acquire and release sequence diagram](../docs/images/readme-diagrams/leader-k8s-sequence-02.png)

`holderIdentity` 에는 매 acquire 마다 새로 생성되는 fencing token 을 저장합니다.
사람이 읽는 audit identity 는 annotation 으로 분리합니다.

| Annotation | 목적 |
| --- | --- |
| `leader.bluetape4k.io/audit-leader-id` | state/audit 표시용 slot leader id 또는 생성 token |
| `leader.bluetape4k.io/node-id` | `LeaderElectionOptions.nodeId` |
| `leader.bluetape4k.io/managed-by` | `bluetape4k-leader-k8s` marker |

이 설계는 같은 JVM 또는 같은 Pod 안의 두 elector 가 동일한 `nodeId` 만으로 소유권을
잘못 공유하지 않게 합니다.

## Core Features

- Blocking / async `LeaderElector` 구현
- Coroutine-native `SuspendLeaderElector` 구현
- Kubernetes `resourceVersion` 기반 owner-conditional release / extension
- `renewTime + leaseDurationSeconds` 기준 만료 Lease takeover
- Lease metadata 와 annotation 에서 `LeaderState` snapshot 생성
- 짧은 action 뒤에도 잠시 leadership 을 유지하는 `minLeaseTime` 지원
- `k8sTest` 태스크의 K3s 기반 통합 테스트

## Usage

```kotlin
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.k8s.KubernetesLeaseLeaderElector
import io.bluetape4k.leader.k8s.KubernetesLeaseOptions
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import kotlin.time.Duration.Companion.seconds

val client = KubernetesClientBuilder().build()
val elector = KubernetesLeaseLeaderElector(
    client = client,
    options = KubernetesLeaseOptions(
        namespace = "operators",
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
import io.bluetape4k.leader.k8s.KubernetesLeaseSuspendLeaderElector

val suspendElector = KubernetesLeaseSuspendLeaderElector(client)

suspendElector.runIfLeader("nightly-sync") {
    syncData()
}
```

Async 사용:

```kotlin
val future = elector.runAsyncIfLeader("webhook-poller") {
    pollWebhooksAsync()
}
```

## Configuration

| 옵션 | 타입 | 기본값 | 설명 |
| --- | --- | --- | --- |
| `namespace` | `String` | `default` | Lease 객체를 저장할 namespace |
| `retryDelay` | `Duration` | `50.milliseconds` | contention 또는 `409 Conflict` 뒤 full-jitter retry 상한 |
| `leaderOptions.waitTime` | `Duration` | `5.seconds` | leadership 획득 최대 대기 시간 |
| `leaderOptions.leaseTime` | `Duration` | `60.seconds` | Kubernetes 에 기록하는 Lease duration |
| `leaderOptions.nodeId` | `String` | process-level default | audit node id annotation |
| `leaderOptions.minLeaseTime` | `Duration` | `0.seconds` | 빠른 action 뒤 최소 leadership 유지 시간 |
| `leaderOptions.autoExtend` | `Boolean` | `false` | action 실행 중 active Lease 자동 연장 |

`lockName` 은 Kubernetes DNS-1123 label 이어야 하며 Lease 이름 제한인 63자를 넘을 수 없습니다.

## RBAC

애플리케이션 service account 는 선택한 namespace 안의 Lease 접근 권한이 필요합니다.

```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: bluetape4k-leader
  namespace: operators
rules:
  - apiGroups: ["coordination.k8s.io"]
    resources: ["leases"]
    verbs: ["get", "list", "watch", "create", "update", "patch", "delete"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: bluetape4k-leader
  namespace: operators
subjects:
  - kind: ServiceAccount
    name: worker
    namespace: operators
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: Role
  name: bluetape4k-leader
```

## Testing

일반 단위 테스트는 K3s 를 제외합니다.

```bash
./gradlew :bluetape4k-leader-k8s:test
```

K3s 기반 통합 테스트는 별도 태스크로 실행합니다.

```bash
./gradlew :bluetape4k-leader-k8s:k8sTest
```

K3s 테스트에는 privileged container 를 지원하는 Docker daemon 이 필요합니다.

## Dependency

Gradle (Kotlin DSL):

```kotlin
dependencies {
    implementation("io.github.bluetape4k.leader:bluetape4k-leader-k8s:$bluetape4kLeaderVersion")
}
```

생성자가 `KubernetesClient` 를 받으므로 Fabric8 Kubernetes Client 는 API dependency 로 노출됩니다.

## License

MIT License
