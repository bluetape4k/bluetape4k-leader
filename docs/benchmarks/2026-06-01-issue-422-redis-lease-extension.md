# 이슈 #422 Redis 리스 연장 벤치마크 - 2026-06-01

문제 #422에는 Redis 리더 선출 리스 연장 동작에 초점을 맞춘 'kotlinx-benchmark' 행이 추가되었습니다. 목표는 프로덕션 코드를 변경하지
않고 Bluetape4k의 공유 'autoExtend' 리스 연장 프로그램과 Lettuce 및 Redisson의 일반 실행을 비교하는 것이었습니다.

동일한 머신 비교에만 이 보고서를 사용하세요. 이는 릴리스 등급 성능 주장이 아닙니다.

## 주의 사항

- 블로킹 및 suspend 행은 동일한 Redis Testcontainers 실행 프로그램을 사용합니다.
- 일반 'runIfLeader' 행은 60초 리스와 빠른 작업을 사용하여 정상적인 실행과 'autoExtend' 활성화 오버헤드를 측정합니다.
- 'runIfLeaderWithRenewalWindow' 행은 90ms 리스 및 45ms 작업 유지를 사용하므로 자동 확장 경로에 갱신 기간이 있습니다.
- 체류 시간이 지배적이므로 동일한 방법 내에서만 해당 행을 비교하세요.
- `redisson-auto-extend`는 bluetape4k의 공유 `LeaderLeaseAutoExtender`를 사용합니다. 현재 Redisson 선출기는 항상 명시적인 `leaseTime`을 사용하여 잠금을 획득하기 때문에 Redisson 기본 watchdog 모드는 표시되지 않습니다.
- 점수 델타는 광범위한 JMH 오류 범위 내에 있습니다. 이 실행을 생산 최적화에 대한 증거로 사용하지 마세요.

## 환경

| Field | Value |
|---|---|
| Date | 2026-06-01 |
| Host | Apple M4 Pro, 12 CPUs, 48 GiB RAM |
| OS | macOS 26.5 arm64 |
| JDK | Oracle GraalVM 21 |
| Gradle | 9.5.1 |
| kotlinx-benchmark | 0.4.17 |
| JMH | 1.37 |
| Docker | Colima, Docker server 29.2.1, Ubuntu 24.04.4 LTS, 3905 MB |
| Warmup | 2 iterations, 1 second each |
| Measurement | 3 iterations, 1 second each |
| Forks | 1 |
| Threads | 1 |

## 명령

```bash
./gradlew :benchmark:benchmarkBenchmark :benchmark:benchmarkAverageTimeBenchmark --no-daemon --no-configuration-cache --rerun-tasks
```

기계가 읽을 수 있는 소스 아티팩트:

- [`2026-06-01-issue-422-redis-lease-extension-throughput.json`](./2026-06-01-issue-422-redis-lease-extension-throughput.json)
- [`2026-06-01-issue-422-redis-lease-extension-average-time.json`](./2026-06-01-issue-422-redis-lease-extension-average-time.json)

## Redis API 차단

높을수록 처리량이 더 좋습니다. 평균 시간에는 낮을수록 좋습니다.

| Scenario | Mode | Throughput (ops/s) | Average time (us/op) | Notes |
|---|---|---:|---:|---|
| `runIfLeader` | lettuce-normal | 1,454.484 ± 812.222 | 696.879 ± 261.682 | 60s lease, fast action |
| `runIfLeader` | lettuce-auto-extend | 1,432.206 ± 673.228 | 674.570 ± 76.338 | Shared auto extender enabled |
| `runIfLeader` | redisson-normal | 1,392.344 ± 156.055 | 721.043 ± 46.545 | 60s lease, fast action |
| `runIfLeader` | redisson-auto-extend | 1,379.041 ± 380.447 | 739.360 ± 42.259 | Shared auto extender, not native watchdog |
| `runIfLeaderWithRenewalWindow` | lettuce-normal | 18.858 ± 2.142 | 52,787.594 ± 13,078.335 | 90ms lease, 45ms action dwell |
| `runIfLeaderWithRenewalWindow` | lettuce-auto-extend | 19.191 ± 3.072 | 52,012.788 ± 14,742.520 | Renewal-window comparison row |
| `runIfLeaderWithRenewalWindow` | redisson-normal | 18.540 ± 4.514 | 52,495.646 ± 13,993.629 | 90ms lease, 45ms action dwell |
| `runIfLeaderWithRenewalWindow` | redisson-auto-extend | 19.150 ± 6.465 | 51,782.799 ± 5,184.910 | Shared auto extender, not native watchdog |

## Redis API 일시중단

| Scenario | Mode | Throughput (ops/s) | Average time (us/op) | Notes |
|---|---|---:|---:|---|
| `runIfLeader` | lettuce-normal | 1,442.249 ± 772.451 | 668.478 ± 280.073 | 60s lease, fast action |
| `runIfLeader` | lettuce-auto-extend | 1,413.118 ± 434.324 | 693.538 ± 206.127 | Shared auto extender enabled |
| `runIfLeader` | redisson-normal | 1,382.143 ± 173.134 | 718.507 ± 233.162 | 60s lease, fast action |
| `runIfLeader` | redisson-auto-extend | 1,363.848 ± 134.125 | 728.479 ± 177.469 | Shared auto extender, not native watchdog |
| `runIfLeaderWithRenewalWindow` | lettuce-normal | 18.757 ± 6.519 | 53,820.084 ± 30,715.585 | 90ms lease, 45ms action dwell |
| `runIfLeaderWithRenewalWindow` | lettuce-auto-extend | 18.876 ± 0.844 | 52,182.685 ± 17,376.505 | Renewal-window comparison row |
| `runIfLeaderWithRenewalWindow` | redisson-normal | 18.603 ± 7.860 | 53,558.941 ± 19,665.787 | 90ms lease, 45ms action dwell |
| `runIfLeaderWithRenewalWindow` | redisson-auto-extend | 19.214 ± 8.932 | 51,883.433 ± 6,959.355 | Shared auto extender, not native watchdog |

## 결정

생산 최적화가 이루어지지 않았습니다. 벤치마크는 현재 노출된 공공 선거인 행동에 대한 적용 범위를 설정하고 기본 Redisson 감시 벤치마킹이 정직하게
측정되기 전에 별도의 API 또는 생산 행동 변경이 필요하다는 것을 문서화합니다.

## 확인

- `./gradlew :benchmark:compileBenchmarkKotlin --no-daemon`
- `./gradlew :benchmark:benchmarkBenchmark :benchmark:benchmarkAverageTimeBenchmark --no-daemon --no-configuration-cache --rerun-tasks`
