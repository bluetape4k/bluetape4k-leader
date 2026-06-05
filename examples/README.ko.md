# bluetape4k leader 예제

[English](README.md) | 한국어

이 디렉터리는 bluetape4k leader-election backend와 integration module을 실행 가능한 예제로 보여줍니다.
각 예제는 여러 replica 중 하나만 job을 실행하거나, rollout 작업을 gate하거나, control-plane loop를 조정하거나,
leader metric을 노출하는 운영 시나리오 하나에 집중합니다.

## 예제 맵

| 예제 | Backend / storage | 시나리오 | 적합한 경우 | 명령 |
|---|---|---|---|---|
| [batch-scheduler](batch-scheduler/README.ko.md) | Redis / Lettuce | 여러 애플리케이션 replica 중 하나만 주기 배치 job을 실행합니다. | Cron, Quartz, Spring scheduled job을 한 번만 실행해야 할 때 | `./gradlew :examples:batch-scheduler:run` |
| [cache-warmer](cache-warmer/README.ko.md) | Hazelcast | 여러 노드가 경쟁해 cache partition별 warming을 한 번만 수행합니다. | Partitioned cache preload에서 중복 실행을 막고 싶을 때 | `./gradlew :examples:cache-warmer:run` |
| [consul-maintenance](consul-maintenance/README.ko.md) | Consul Session + KV | 한 service instance만 maintenance 또는 drain 작업을 수행합니다. | Service drain, endpoint rotation, maintenance window | `./gradlew :examples:consul-maintenance:run` |
| [dynamodb-export](dynamodb-export/README.ko.md) | DynamoDB Local / AWS DynamoDB | 한 노드만 scheduled export record를 쓰고 나머지는 skip합니다. | Billing export, scheduled report, AWS-only service | `./gradlew :examples:dynamodb-export:run` |
| [zookeeper-scheduler](zookeeper-scheduler/README.ko.md) | ZooKeeper / Curator | 한 node만 legacy scheduled job을 실행하고 경쟁 node는 skip합니다. | 기존 ZooKeeper/Curator 기반 서비스 | `./gradlew :examples:zookeeper-scheduler:run` |
| [etcd-reconciler](etcd-reconciler/README.ko.md) | etcd v3 | 한 control-plane node만 desired resource를 적용합니다. | Reconciler loop, infrastructure controller | `./gradlew :examples:etcd-reconciler:run` |
| [k8s-lease](k8s-lease/README.ko.md) | Kubernetes Lease | Lease acquire, conflict, release, reacquire 동작을 검증합니다. | K3s 기반 low-level Lease API 검증 | `./gradlew :examples:k8s-lease:k8sTest` |
| [k8s-operator](k8s-operator/README.ko.md) | Kubernetes Lease + Spring Boot | 여러 pod 중 하나만 operator reconcile loop를 실행합니다. | Kubernetes operator, controller loop | `./gradlew :examples:k8s-operator:run` |
| [ktor-app](ktor-app/README.ko.md) | Ktor + Redis / Lettuce | leader-scheduled background aggregation job을 실행합니다. | Background task가 있는 Ktor service | `./gradlew :examples:ktor-app:run` |
| [migration-gate](migration-gate/README.ko.md) | Exposed JDBC | Rolling deploy 중 schema migration을 gate합니다. | Startup migration, idempotent schema change | `./gradlew :examples:migration-gate:run` |
| [prometheus-dashboard](prometheus-dashboard/README.ko.md) | Spring Boot + Lettuce + Micrometer | leader AOP metric을 노출하고 dashboard로 확인합니다. | Annotation 기반 leader job 관측성 확인 | `./gradlew :examples:prometheus-dashboard:bootRun` |
| [rate-limiter](rate-limiter/README.ko.md) | Redis / Lettuce + Bucket4j | Probe dispatch를 한 번만 수행하고 shared quota를 적용합니다. | Leader-dispatched work와 distributed rate limit 조합 | `./gradlew :examples:rate-limiter:run` |
| [redisson-watchdog](redisson-watchdog/README.ko.md) | Redis / Redisson | Auto-extension으로 장시간 leader job의 lease를 유지합니다. | 초기 lease time보다 오래 걸릴 수 있는 작업 | `./gradlew :examples:redisson-watchdog:run` |
| [strategic-election](strategic-election/README.ko.md) | Local strategic election | Weighted score로 가장 적합한 node를 선택합니다. | Backend와 무관한 candidate selection을 먼저 보고 싶을 때 | `./gradlew :examples:strategic-election:run` |
| [tenant-aggregator](tenant-aggregator/README.ko.md) | Exposed R2DBC | Tenant별 coroutine aggregation loop를 하나씩 실행합니다. | Multi-tenant aggregation, async database workload | `./gradlew :examples:tenant-aggregator:run` |
| [virtual-thread-runner](virtual-thread-runner/README.ko.md) | Local virtual-thread elector | Blocking leader work를 Java virtual thread에서 실행합니다. | 고동시성 service에서 bounded blocking task를 처리할 때 | `./gradlew :examples:virtual-thread-runner:run` |
| [webhook-poller](webhook-poller/README.ko.md) | MongoDB | 한 poller만 선출해 webhook event를 atomic하게 claim합니다. | At-least-once webhook polling, event handler | `./gradlew :examples:webhook-poller:run` |

## 예제 선택 기준

- 운영 환경과 비슷한 예제를 보려면 이미 사용 중인 backend/storage부터 확인하세요.
- Service stack에 Redis가 있다면 Redis / Lettuce 또는 Redis / Redisson 예제를 먼저 고려합니다.
- Leadership 상태를 애플리케이션 데이터와 함께 두고 싶다면 Exposed JDBC 또는 R2DBC 예제가 적합합니다.
- AWS-managed storage가 coordination boundary이고 로컬 검증은 DynamoDB Local로 충분하다면 DynamoDB 예제가 적합합니다.
- 기존 ZooKeeper/Curator estate에서 legacy scheduled job을 조율하려면 ZooKeeper 예제가 적합합니다.
- Control-plane이나 service-discovery 인프라와 가까운 문제라면 Kubernetes Lease, etcd, Consul 예제를 확인하세요.
- 외부 인프라 없이 API 모양을 보고 싶다면 local strategic 또는 virtual-thread 예제가 가볍습니다.
- Spring AOP leader-election annotation이 어떤 metric을 내보내는지 보려면 `prometheus-dashboard`를 사용하세요.

## 실행

대부분의 예제는 Gradle `run` task를 제공하며, `build.gradle.kts`의 `mainClass`를 통해 IDE에서도 실행할 수 있습니다.
Infrastructure-backed 예제는 bluetape4k Testcontainers launcher를 사용하므로 README에서 별도 endpoint 옵션을 설명하지 않는 한 Docker가 필요합니다.

```bash
./gradlew :examples:batch-scheduler:run
./gradlew :examples:consul-maintenance:test
./gradlew :examples:zookeeper-scheduler:run
./gradlew :examples:prometheus-dashboard:bootRun
```

Kubernetes 예제는 K3s Testcontainers setup을 지원하는 local Docker daemon 또는 CI runner가 필요합니다.
`k8s-lease` 예제의 실제 cluster 검증은 `k8sTest` task로 분리되어 있습니다.
