# 이슈 #524 Kubernetes 리스 시나리오 벤치마크

이 벤치마크는 K3s 지원 Kubernetes 리스 적용 범위를 기본적인 성공적인 'runIfLeader' 경로에서 상태 저장 리스 시나리오로 확장합니다.

- 공공 선거를 통해 새로운 획득
- 사전 보유 리스는 공공 선거인을 거치지 않고 건너뜁니다.
- 만료된 보유자는 공공 선거인을 통해 인수됩니다.
- 직접 Kubernetes API 서버 프로브로 동일 소유자 리스 갱신 업데이트
- 직접 Kubernetes API 서버 프로브로 오래된 'resourceVersion' 업데이트 충돌이 발생함

갱신 및 충돌 행은 Kubernetes Lease API 서버 업데이트/충돌 대기 시간을 의도적으로 격리합니다. 이는 조치 실행 비용이 아니며 전체 선거인
획득+해제 행으로 순위를 지정해서는 안 됩니다.

## 명령

Gradle 작업 검색 및 JMH jar 생성:

```bash
./gradlew :benchmark:tasks --all --no-daemon --no-configuration-cache --console=plain
./gradlew :benchmark:kubernetesBenchmarkBenchmarkJar --no-daemon --no-configuration-cache --console=plain
```

측정된 연기 스냅샷:

```bash
java -jar benchmark/build/benchmarks/kubernetesBenchmark/jars/benchmark-kubernetesBenchmark-jmh-0.5.0-JMH.jar \
  '.*KubernetesBackendLeaderElectorBenchmark.*' \
  -bm thrpt -tu s -f 1 -wi 1 -i 1 -w 200ms -r 200ms \
  -rf json -rff docs/benchmarks/2026-07-02-issue-524-kubernetes-scenarios-throughput.json

java -jar benchmark/build/benchmarks/kubernetesBenchmark/jars/benchmark-kubernetesBenchmark-jmh-0.5.0-JMH.jar \
  '.*KubernetesBackendLeaderElectorBenchmark.*' \
  -bm avgt -tu us -f 1 -wi 1 -i 1 -w 200ms -r 200ms \
  -rf json -rff docs/benchmarks/2026-07-02-issue-524-kubernetes-scenarios-average-time.json
```

여기서는 직접적인 JMH jar 경로가 사용되므로 짧은 K3s 연기 실행이 문제별 JSON 파일을 작성할 수 있습니다. 표준 검색/빌드 표면은
'kubernetesBenchmark' Gradle 대상으로 유지됩니다.

## 결과

| Scenario | Throughput (ops/s) | Average time (us/op) | Interpretation |
|---|---:|---:|---|
| `blockingFreshAcquire` | 82.297 | 12,810.608 | Public blocking elector creates/acquires/releases a fresh Lease. |
| `blockingPreHeldSkip` | 661.149 | 1,547.237 | Public blocking elector observes an active external holder and skips. |
| `blockingExpiredTakeover` | 89.767 | 9,137.928 | Public blocking elector takes over an expired holder and releases. |
| `blockingLeaseRenewalUpdate` | 208.781 | 4,209.766 | Direct Lease API update for a same-holder renewal window. |
| `blockingResourceVersionConflict` | 539.753 | 3,039.625 | Direct stale `resourceVersion` update that returns Kubernetes 409. |
| `suspendFreshAcquire` | 90.055 | 10,753.638 | Suspend elector acquire+release path wrapped in `Dispatchers.IO`. |
| `suspendPreHeldSkip` | 465.583 | 2,690.823 | Suspend elector active-holder skip path. |
| `suspendExpiredTakeover` | 97.097 | 8,634.000 | Suspend elector expired-holder takeover path. |
| `suspendLeaseRenewalUpdate` | 258.746 | 4,720.792 | Direct Lease API renewal update from the suspend benchmark lane. |
| `suspendResourceVersionConflict` | 425.577 | 2,181.023 | Direct stale `resourceVersion` conflict from the suspend benchmark lane. |

![Kubernetes 리스 시나리오
처리량](../images/readme-charts/leader-kubernetes-scenarios-throughput-chart-01.png)

![Kubernetes 리스 시나리오 대기
시간](../images/readme-charts/leader-kubernetes-scenarios-latency-chart-01.png)

## 해석

- 미리 보유된 건너뛰기 경로는 활성 보유자를 읽고 리스를 작성하지 않고 반환하기 때문에 새로 획득하거나 만료된 인수보다 훨씬 빠릅니다.
- 이 짧은 연기 스냅샷에서 새로운 획득은 만료된 인수보다 느립니다. 새로운 경로는 릴리스 전에 리스를 생성하는 반면 인계는 기존 리스 리소스를
- 업데이트합니다.
- 갱신 및 충돌 행은 의도적으로 직접적인 API 프로브입니다. 로컬 사용자 작업 실행과 API 서버 업데이트/충돌 비용을 분리하는 데 도움이 됩니다.
- 블로킹 행과 일시중단 행은 비슷하지만 동일하지는 않습니다. 일시 중지 공개 선출기 호출은 'Dispatchers.IO'에서 Fabric8 작업을 래핑하는
- 반면, 직접 일시 중지 프로브는 Lease API 호출 주위에 동일한 IO 경계를 사용합니다.
- 이것은 하나의 포크, 하나의 스레드, 하나의 워밍업 및 하나의 200ms 측정 반복을 포함하는 짧은 K3s Testcontainers 연기
- 스냅샷입니다. 프로덕션 튜닝에 숫자를 사용하기 전에 더 긴 창에서 반복하세요.

## 유물

- 처리량 JSON:
- [`2026-07-02-issue-524-kubernetes-scenarios-throughput.json`](2026-07-02-issue-524-kubernetes-scenarios-throughput.json)
- 평균 시간 JSON:
- [`2026-07-02-issue-524-kubernetes-scenarios-average-time.json`](2026-07-02-issue-524-kubernetes-scenarios-average-time.json)
- 처리량 차트:
- [`leader-kubernetes-scenarios-throughput-chart-01.svg`](../images/readme-charts/leader-kubernetes-scenarios-throughput-chart-01.svg)
- /
- [`leader-kubernetes-scenarios-throughput-chart-01.png`](../images/readme-charts/leader-kubernetes-scenarios-throughput-chart-01.png)
- 지연 시간 차트:
- [`leader-kubernetes-scenarios-latency-chart-01.svg`](../images/readme-charts/leader-kubernetes-scenarios-latency-chart-01.svg)
- /
- [`leader-kubernetes-scenarios-latency-chart-01.png`](../images/readme-charts/leader-kubernetes-scenarios-latency-chart-01.png)
