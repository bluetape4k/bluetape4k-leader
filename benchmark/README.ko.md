# bluetape4k-leader benchmark

[English](./README.md) | 한국어

이 non-published 모듈은 leader election backend를 같은 기준으로 비교하기
위한 `kotlinx-benchmark` suite를 담고 있습니다. JVM runner는 JMH이며,
benchmark source set은 `benchmark/src/benchmark/kotlin` 아래에 있습니다.

아래 결과는 같은 장비에서 전/후 비교를 하기 위한 기준선입니다. 릴리스급
성능 보증으로 해석하면 안 됩니다.

## Benchmark Command

```bash
./gradlew :benchmark:benchmarkBenchmark :benchmark:benchmarkAverageTimeBenchmark --no-configuration-cache --rerun-tasks
./gradlew :benchmark:kubernetesBenchmarkBenchmark :benchmark:kubernetesBenchmarkAverageTimeBenchmark --no-configuration-cache --rerun-tasks
```

2026-05-21 기준선은 fork 1, thread 1, warmup 2회, 1초 measurement 3회로
측정했습니다. 전체 환경과 주의사항은
[`docs/benchmarks/2026-05-21-leader-cross-backend-baseline.md`](../docs/benchmarks/2026-05-21-leader-cross-backend-baseline.md)에
기록되어 있습니다.

Issue #405는 2026-05-29 같은 장비에서 측정한 PostgreSQL 및 MySQL 행을
추가합니다. Blocking SQL 행은 Exposed JDBC를 사용하고, suspend SQL 행은
Exposed R2DBC를 사용합니다. Kubernetes는 Fabric8 client가 Vert.x 4 /
Netty 4.1 runtime을 필요로 하고, 기본 target은 etcd를 위해 Vert.x 5를
유지해야 하므로 별도 benchmark target으로 실행합니다. 원본 JSON은 다음
경로에 보존했습니다.

- [`docs/benchmarks/2026-05-29-issue-405-rdb-backend-throughput.json`](../docs/benchmarks/2026-05-29-issue-405-rdb-backend-throughput.json)
- [`docs/benchmarks/2026-05-29-issue-405-rdb-backend-average-time.json`](../docs/benchmarks/2026-05-29-issue-405-rdb-backend-average-time.json)
- [`docs/benchmarks/2026-05-29-issue-418-kubernetes-throughput.json`](../docs/benchmarks/2026-05-29-issue-418-kubernetes-throughput.json)
- [`docs/benchmarks/2026-05-29-issue-418-kubernetes-average-time.json`](../docs/benchmarks/2026-05-29-issue-418-kubernetes-average-time.json)

Issue #422는 2026-06-01 같은 장비에서 측정한 Redis lease-extension 전용 행을
추가합니다. 이 행들은 Lettuce와 Redisson의 일반 실행을 shared `autoExtend`
lease extender와 비교합니다. 현재 Redisson elector는 항상 명시적 `leaseTime`을
전달하므로 Redisson native watchdog mode는 측정 대상에 포함하지 않았습니다.
원본 JSON은 다음 경로에 보존했습니다.

- [`docs/benchmarks/2026-06-01-issue-422-redis-lease-extension-throughput.json`](../docs/benchmarks/2026-06-01-issue-422-redis-lease-extension-throughput.json)
- [`docs/benchmarks/2026-06-01-issue-422-redis-lease-extension-average-time.json`](../docs/benchmarks/2026-06-01-issue-422-redis-lease-extension-average-time.json)

## Charts

분산 환경 backend 차트는 infrastructure backend 간 차이가 보이도록 local
및 H2 행을 제외했습니다. Kubernetes는 별도 runtime classpath에서 실행하므로
해당 표 옆에 별도 차트를 둡니다.

![Leader benchmark distributed throughput](../docs/images/readme-charts/leader-benchmark-distributed-throughput-chart-01.png)

![Leader benchmark distributed latency](../docs/images/readme-charts/leader-benchmark-distributed-latency-chart-01.png)

Issue #329는 같은 benchmark harness로 history recorder 전/후 비교도
기록합니다.

![Leader history recorder self-improve throughput](../docs/images/readme-charts/leader-history-self-improve-throughput-chart-01.png)

## Latest Self-Improve Result

Issue #329는 benchmark harness를 바꾸지 않고 history recorder sanitizer의
safe fast path를 최적화했습니다. 같은 throughput command에서 local history
행은 다음처럼 개선되었습니다.

| Benchmark | Baseline (ops/s) | After (ops/s) | Delta |
|---|---:|---:|---:|
| `HistoryRecorder.blockingInMemoryAcquireComplete` | 5,601,881.043 | 20,018,125.709 | +257.35% |
| `HistoryRecorder.blockingNoopAcquireComplete` | 7,642,848.188 | 62,740,146.724 | +720.90% |
| `HistoryRecorder.suspendInMemoryAcquireComplete` | 4,843,511.108 | 11,441,889.888 | +136.23% |
| `HistoryRecorder.suspendNoopAcquireComplete` | 5,257,310.052 | 23,153,305.712 | +340.40% |

상세:
[`docs/benchmarks/2026-05-21-issue-329-leader-history-recorder-self-improve.md`](../docs/benchmarks/2026-05-21-issue-329-leader-history-recorder-self-improve.md).

## Cross-Backend Results

Throughput은 높을수록 좋고, average time은 낮을수록 좋습니다.

## Redis Lease Extension Results

Throughput은 높을수록 좋고, average time은 낮을수록 좋습니다.

일반 `runIfLeader` 행은 60초 lease와 빠른 action으로 일반 실행과
`autoExtend` 활성화 overhead를 비교합니다. `runIfLeaderWithRenewalWindow`
행은 90ms lease와 45ms action dwell을 사용해 auto-extension path가 renewal
window를 갖도록 했습니다. 이 행들은 dwell 시간이 지배적이므로 같은 method
안에서만 비교하세요.

`redisson-auto-extend`는 Redisson native watchdog renewal이 아니라
bluetape4k의 shared `LeaderLeaseAutoExtender`를 사용합니다. 관측 차이는 넓은
JMH error bound 안에 있으므로, 이 수치만으로 production 최적화를 정당화하지
않습니다.

### Blocking Redis API

| Scenario | Mode | Throughput (ops/s) | Average time (us/op) | Notes |
|---|---|---:|---:|---|
| `runIfLeader` | lettuce-normal | 1,454.484 ± 812.222 | 696.879 ± 261.682 | 60s lease, 빠른 action |
| `runIfLeader` | lettuce-auto-extend | 1,432.206 ± 673.228 | 674.570 ± 76.338 | Shared auto extender 활성화 |
| `runIfLeader` | redisson-normal | 1,392.344 ± 156.055 | 721.043 ± 46.545 | 60s lease, 빠른 action |
| `runIfLeader` | redisson-auto-extend | 1,379.041 ± 380.447 | 739.360 ± 42.259 | Shared auto extender, native watchdog 아님 |
| `runIfLeaderWithRenewalWindow` | lettuce-normal | 18.858 ± 2.142 | 52,787.594 ± 13,078.335 | 90ms lease, 45ms action dwell |
| `runIfLeaderWithRenewalWindow` | lettuce-auto-extend | 19.191 ± 3.072 | 52,012.788 ± 14,742.520 | Renewal-window 비교 행 |
| `runIfLeaderWithRenewalWindow` | redisson-normal | 18.540 ± 4.514 | 52,495.646 ± 13,993.629 | 90ms lease, 45ms action dwell |
| `runIfLeaderWithRenewalWindow` | redisson-auto-extend | 19.150 ± 6.465 | 51,782.799 ± 5,184.910 | Shared auto extender, native watchdog 아님 |

### Suspend Redis API

| Scenario | Mode | Throughput (ops/s) | Average time (us/op) | Notes |
|---|---|---:|---:|---|
| `runIfLeader` | lettuce-normal | 1,442.249 ± 772.451 | 668.478 ± 280.073 | 60s lease, 빠른 action |
| `runIfLeader` | lettuce-auto-extend | 1,413.118 ± 434.324 | 693.538 ± 206.127 | Shared auto extender 활성화 |
| `runIfLeader` | redisson-normal | 1,382.143 ± 173.134 | 718.507 ± 233.162 | 60s lease, 빠른 action |
| `runIfLeader` | redisson-auto-extend | 1,363.848 ± 134.125 | 728.479 ± 177.469 | Shared auto extender, native watchdog 아님 |
| `runIfLeaderWithRenewalWindow` | lettuce-normal | 18.757 ± 6.519 | 53,820.084 ± 30,715.585 | 90ms lease, 45ms action dwell |
| `runIfLeaderWithRenewalWindow` | lettuce-auto-extend | 18.876 ± 0.844 | 52,182.685 ± 17,376.505 | Renewal-window 비교 행 |
| `runIfLeaderWithRenewalWindow` | redisson-normal | 18.603 ± 7.860 | 53,558.941 ± 19,665.787 | 90ms lease, 45ms action dwell |
| `runIfLeaderWithRenewalWindow` | redisson-auto-extend | 19.214 ± 8.932 | 51,883.433 ± 6,959.355 | Shared auto extender, native watchdog 아님 |

### Blocking API

| Backend | Throughput (ops/s) | Average time (us/op) | Notes |
|---|---:|---:|---|
| local | 2,247,218.689 ± 258,773.085 | 0.467 ± 0.019 | In-process 기준선 |
| exposed-jdbc-h2 | 20,691.932 ± 63,884.249 | 51.079 ± 160.765 | Local H2 SQL layer 기준선 |
| hazelcast | 1,460.936 ± 659.253 | 766.272 ± 423.114 | Testcontainers 기반 분산 환경 backend |
| lettuce | 1,454.659 ± 443.418 | 699.411 ± 276.093 | Testcontainers 기반 Redis backend |
| redisson | 1,415.840 ± 513.959 | 699.703 ± 164.584 | Testcontainers 기반 Redis backend |
| mongo | 843.726 ± 3,644.524 | 1,131.005 ± 1,301.052 | Testcontainers 기반 분산 환경 backend |
| zookeeper | 804.334 ± 336.239 | 1,372.211 ± 588.106 | Testcontainers 기반 분산 환경 backend |
| dynamodb | 722.171 ± 1,582.978 | 1,749.692 ± 7,978.213 | DynamoDB Local |
| consul | 593.610 ± 246.434 | 1,900.576 ± 1,504.614 | Consul container |
| etcd | 443.838 ± 587.372 | 2,167.925 ± 3,258.402 | etcd container |
| exposed-jdbc-postgresql | 80.310 ± 32.723 | 13,925.403 ± 16,904.463 | PostgreSQL Testcontainer 기반 Exposed JDBC |
| exposed-jdbc-mysql | 69.518 ± 59.759 | 15,023.674 ± 26,615.012 | MySQL Testcontainer 기반 Exposed JDBC |

### Suspend API

| Backend | Throughput (ops/s) | Average time (us/op) | Notes |
|---|---:|---:|---|
| local | 786,325.801 ± 212,414.586 | 1.272 ± 0.306 | Coroutine bridge 기준선 |
| exposed-r2dbc-h2 | 5,998.877 ± 17,975.602 | 166.245 ± 440.023 | Local H2 R2DBC layer 기준선 |
| lettuce | 1,402.576 ± 1,400.853 | 675.318 ± 245.705 | Testcontainers 기반 Redis backend |
| redisson | 1,386.653 ± 715.983 | 714.918 ± 188.197 | Testcontainers 기반 Redis backend |
| hazelcast | 1,325.931 ± 1,368.902 | 748.966 ± 89.468 | Testcontainers 기반 분산 환경 backend |
| mongo | 798.439 ± 1,869.556 | 4,333.477 ± 47,816.200 | 노이즈가 큰 행; tuning 전 재측정 필요 |
| zookeeper | 670.564 ± 873.137 | 1,397.254 ± 1,293.725 | Testcontainers 기반 분산 환경 backend |
| consul | 563.158 ± 1,243.537 | 1,701.845 ± 902.436 | Consul container |
| dynamodb | 510.161 ± 1,882.141 | 1,947.304 ± 5,811.616 | DynamoDB Local |
| etcd | 467.461 ± 300.083 | 2,239.412 ± 2,885.971 | etcd container |
| exposed-r2dbc-postgresql | 53.588 ± 139.427 | 17,736.983 ± 13,072.732 | PostgreSQL Testcontainer 기반 Exposed R2DBC |
| exposed-r2dbc-mysql | 65.204 ± 58.647 | 17,616.078 ± 8,183.403 | MySQL Testcontainer 기반 Exposed R2DBC |

## Kubernetes Results

Kubernetes는 K3s Testcontainers wrapper를 사용하며, Fabric8 runtime이 기본
preview backend classpath를 downgrade하지 않도록 `kubernetesBenchmark`
source set에서 별도로 실행합니다.

| Benchmark | Throughput (ops/s) | Average time (us/op) | Notes |
|---|---:|---:|---|
| `Kubernetes.blockingRunIfLeader` | 171.525 ± 160.477 | 5,835.436 ± 8,251.639 | K3s 기반 Lease lock |
| `Kubernetes.suspendRunIfLeader` | 164.687 ± 57.773 | 6,075.660 ± 4,944.763 | K3s 기반 Lease lock |

![Kubernetes benchmark throughput](../docs/images/readme-charts/leader-benchmark-kubernetes-throughput-chart-01.png)

![Kubernetes benchmark latency](../docs/images/readme-charts/leader-benchmark-kubernetes-latency-chart-01.png)

## Local Core Rows

이 행들은 기존 2026-05-21 cross-backend 기준선입니다. Issue #329 이후 수치는
위 self-improve 섹션을 기준으로 보세요.

| Benchmark | Throughput (ops/s) | Average time (us/op) |
|---|---:|---:|
| `LocalLeader.blockingRunIfLeader` | 2,250,949.108 ± 167,049.822 | 0.451 ± 0.263 |
| `LocalLeader.asyncOnlyRunIfLeader` | 2,230,952.540 ± 248,386.525 | 0.447 ± 0.121 |
| `LocalLeader.completableFutureRunIfLeader` | 2,231,412.162 ± 324,642.886 | 0.445 ± 0.080 |
| `LocalLeader.suspendRunIfLeader` | 838,923.760 ± 388,344.058 | 1.172 ± 0.243 |
| `LocalLeader.virtualThreadRunIfLeader` | 138,705.240 ± 7,476.129 | 7.377 ± 1.244 |
| `HistoryRecorder.blockingNoopAcquireComplete` | 7,356,503.438 ± 2,672,535.544 | 0.129 ± 0.001 |
| `HistoryRecorder.blockingInMemoryAcquireComplete` | 5,828,846.244 ± 233,849.435 | 0.171 ± 0.014 |
| `HistoryRecorder.suspendNoopAcquireComplete` | 5,300,097.780 ± 186,734.921 | 0.164 ± 0.007 |
| `HistoryRecorder.suspendInMemoryAcquireComplete` | 4,784,646.339 ± 1,302,210.407 | 0.206 ± 0.032 |

## Interpretation

- canonical ranking metric은 throughput이며 average time은 보조 latency
  evidence입니다.
- 분산 backend는 분산 backend끼리 비교하세요. Local H2 행을 Redis,
  Hazelcast, ZooKeeper, MongoDB, PostgreSQL, MySQL 같은 분산 시스템 backend와
  직접 순위 비교하면 안 됩니다.
- JVM 내부 coordination은 H2 leader election 대신 local lock primitive를
  우선 사용하세요. H2는 local SQL/R2DBC shape check로만 남깁니다.
- local 행은 network/storage round trip이 없는 framework/API overhead를
  분리해서 보여줍니다.
- benchmark setup은 측정 전 smoke `runIfLeader` check를 수행하므로,
  infrastructure 연결 실패가 잘못된 빠른 경로로 측정되지 않습니다.
- 특히 DynamoDB, etcd, Kubernetes, suspend MongoDB처럼 노이즈가 큰 행은
  최적화 판단 전에 반복 측정하세요.

## Benchmark Classes

| Class | Scenario |
|---|---|
| `BackendLeaderElectorBenchmark` | Blocking `runIfLeader`: local, Redis, Exposed JDBC H2/PostgreSQL/MySQL, MongoDB, Hazelcast, ZooKeeper, Consul, etcd, DynamoDB |
| `SuspendBackendLeaderElectorBenchmark` | Suspend `runIfLeader`: local, Redis, Exposed R2DBC H2/PostgreSQL/MySQL, MongoDB, Hazelcast, ZooKeeper, Consul, etcd, DynamoDB |
| `RedisLeaseExtensionBenchmark` | Blocking Lettuce/Redisson 일반 실행과 shared `autoExtend` lease-extension 행 |
| `SuspendRedisLeaseExtensionBenchmark` | Suspend Lettuce/Redisson 일반 실행과 shared `autoExtend` lease-extension 행 |
| `KubernetesBackendLeaderElectorBenchmark` | 별도 Vert.x 4 runtime에서 K3s 기반 Kubernetes Lease lock의 blocking/suspend `runIfLeader` 측정 |
| `LocalLeaderElectorBenchmark` | Local blocking, async, completable-future, suspend, virtual-thread elector overhead |
| `HistoryRecorderBenchmark` | No-op 및 in-memory leader history recorder overhead |
