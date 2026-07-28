# 선두 크로스백엔드 벤치마크 기준 - 2026-05-21

이는 문제 #327에 대한 로컬 개발자-머신 기준입니다. Gradle 벤치마크 프런트엔드로 'kotlinx-benchmark'를 사용하고 JVM 백엔드로
JMH를 사용합니다. 릴리스 등급 성능 주장이 아닌 동일 시스템 전후 비교에 사용하세요.

## 주의 사항

- 벤치마크 소스 세트는 `benchmark/src/benchmark/kotlin`에 있습니다.
- JVM 실행기는 'kotlinx-benchmark'를 통한 JMH입니다. 벤치마크 클래스는 여전히 JMH 주석과 `Blackhole`을 사용합니다.
- Testcontainers 지원 행에는 Docker 네트워크 및 클라이언트 왕복 비용이 포함됩니다.
- H2 행은 분산 잠금 백엔드 클레임이 아닌 로컬 인메모리 데이터베이스 기준선입니다.
- suspend 행에는 측정된 API가 일시 중단되지 않은 JMH 메서드에서 호출되는 벤치마크 하네스 브리지 비용이 포함됩니다.
- 벤치마크 설정은 측정 전에 'runIfLeader' 검사를 수행하므로 실패한 인프라 연결이 잘못된 빠른 경로 행으로 바뀌지 않습니다.
- suspend MongoDB 행은 이 단기 실행에서 잡음이 많았습니다. 튜닝 결정에 사용하기 전에 반복하세요.

## 환경

| Field | Value |
|---|---|
| Date | 2026-05-21 |
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
| Logging | INFO root / `io.bluetape4k`, selected noisy libraries WARN |

## 명령

```bash
./gradlew :benchmark:benchmarkBenchmark :benchmark:benchmarkAverageTimeBenchmark --no-configuration-cache --rerun-tasks
```

기계가 읽을 수 있는 소스 아티팩트:

- `benchmark/build/reports/benchmarks/main/2026-05-21T13.57.04.377621/benchmark.json`
- `benchmark/build/reports/benchmarks/averageTime/2026-05-21T13.57.04.377621/benchmark.json`

## 크로스백엔드 결과

높을수록 처리량이 더 좋습니다. 평균 시간에는 낮을수록 좋습니다.

### 블로킹 API

| Backend | Throughput (ops/s) | Average time (us/op) |
|---|---:|---:|
| local | 2,204,166.553 ± 387,424.052 | 0.445 ± 0.052 |
| exposed-jdbc-h2 | 20,138.374 ± 59,295.930 | 49.943 ± 162.508 |
| hazelcast | 1,457.277 ± 213.303 | 693.926 ± 61.127 |
| redisson | 1,354.629 ± 2,657.106 | 715.517 ± 217.899 |
| lettuce | 1,054.204 ± 11,495.384 | 703.769 ± 153.427 |
| mongo | 934.619 ± 691.550 | 1,105.806 ± 87.387 |
| zookeeper | 760.439 ± 1,079.874 | 1,252.265 ± 1,393.136 |

### API 일시중단

| Backend | Throughput (ops/s) | Average time (us/op) |
|---|---:|---:|
| local | 793,107.864 ± 193,258.001 | 1.250 ± 0.374 |
| exposed-r2dbc-h2 | 6,393.060 ± 18,208.172 | 162.539 ± 440.562 |
| lettuce | 1,458.073 ± 240.569 | 648.530 ± 311.462 |
| redisson | 1,395.999 ± 248.707 | 713.728 ± 121.088 |
| hazelcast | 1,393.962 ± 693.802 | 701.224 ± 136.723 |
| mongo | 829.311 ± 666.735 | 3,334.853 ± 61,304.680 |
| zookeeper | 721.758 ± 938.116 | 1,250.279 ± 947.488 |

## 로컬 핵심 행

| Benchmark | Throughput (ops/s) | Average time (us/op) |
|---|---:|---:|
| LocalLeader.blockingRunIfLeader | 2,250,949.108 ± 167,049.822 | 0.451 ± 0.263 |
| LocalLeader.asyncOnlyRunIfLeader | 2,230,952.540 ± 248,386.525 | 0.447 ± 0.121 |
| LocalLeader.completableFutureRunIfLeader | 2,231,412.162 ± 324,642.886 | 0.445 ± 0.080 |
| LocalLeader.suspendRunIfLeader | 838,923.760 ± 388,344.058 | 1.172 ± 0.243 |
| LocalLeader.virtualThreadRunIfLeader | 138,705.240 ± 7,476.129 | 7.377 ± 1.244 |
| HistoryRecorder.blockingNoopAcquireComplete | 7,356,503.438 ± 2,672,535.544 | 0.129 ± 0.001 |
| HistoryRecorder.blockingInMemoryAcquireComplete | 5,828,846.244 ± 233,849.435 | 0.171 ± 0.014 |
| HistoryRecorder.suspendNoopAcquireComplete | 5,300,097.780 ± 186,734.921 | 0.164 ± 0.007 |
| HistoryRecorder.suspendInMemoryAcquireComplete | 4,784,646.339 ± 1,302,210.407 | 0.206 ± 0.032 |

## 관찰

- 중앙 `benchmark/` 모듈은 이제 로컬 코어, 백엔드 블로킹 및 백엔드 행 일시 중지에 대해 하나의 유사한 명령을 제공합니다.
- 로컬 핫 경로는 Docker 지원 분산 백엔드 행보다 3배 더 빠릅니다. 향후 최적화에서는 로컬 API 오버헤드를 백엔드 왕복 비용과 분리해야 합니다.
- Redis Lettuce, Redis Redisson 및 Hazelcast는 이 짧은 단일 스레드 핫 경로 실행에서 가깝습니다.
- 노출된 H2 행은 스토리지가 로컬 인메모리 H2이기 때문에 빠릅니다. 이는 SQL 계층 오버헤드에 유용하지만 분산 시스템으로서 Redis,
- Hazelcast, ZooKeeper 또는 MongoDB와 비교하여 순위를 매겨서는 안 됩니다.
- Suspend MongoDB는 순위를 매기기 전에 반복 실행이나 더 긴 측정 프로필이 필요합니다.

## 다음 작품

- 문제 #328은 위의 두 JSON 파일에서 README 차트를 생성해야 합니다.
- 문제 #329 자체 개선 작업에서는 처리량을 기본 측정항목으로, 평균 시간을 보조 대기 시간 증거로 사용해야 합니다.
- 튜닝 후보의 경우 동일한 명령, 포크 수, JVM, Docker 런타임 및 로깅 수준을 유지하세요.
