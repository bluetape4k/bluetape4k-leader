# 리더 코어 벤치마크 기준 — 2026-05-21

이는 문제 #326에 대한 로컬 개발자-머신 기준입니다. 이를 사용하여 동일한 시스템 및 JVM의 향후 변경 사항을 비교합니다. 릴리스 등급 처리량 청구로
처리하지 마십시오.

업데이트: 문제 #327은 이러한 벤치마크 시나리오를 `leader-core/src/jmh`에서 게시되지 않은 중앙 `benchmark/` 모듈로 이동하고
JVM 백엔드로 JMH를 사용하여 Gradle 프런트엔드를 `kotlinx-benchmark`로 전환했습니다. 아래 숫자를 PR #330 역사적 기준으로
유지하세요. 현재 비교를 위해서는 `docs/benchmarks/2026-05-21-leader-cross-backend-baseline.md`를
사용하세요.

## 주의 사항

- 코루틴 벤치마크는 JMH가 '일시 중지' 벤치마크 메서드를 직접 호출할 수 없기 때문에 'runBlocking {}'을 사용합니다. 결과에는 브리지
- 비용이 포함됩니다.
- 가상 스레드 벤치마크에는 로컬 잠금 획득뿐만 아니라 가상 스레드 제출 및 예약 비용도 포함됩니다.
- 로컬 선출기 벤치마크는 경쟁이 없는 단일 스레드 핫 경로 측정입니다.
- JMH는 이 JVM에서 컴파일러 Blackhole을 사용했습니다. 향후 실행을 비교할 때 동일한 JVM 및 블랙홀 모드를 유지하세요.

## 환경

| Field | Value |
|---|---|
| Date | 2026-05-21 |
| Host | Apple M4 Pro, 12 CPUs, 48 GiB RAM |
| OS | macOS 26.5 arm64 |
| JDK | Oracle GraalVM 21.0.11 |
| Gradle | 9.5.1 |
| JMH | 1.37 |
| Warmup | 2 iterations, 1 second each |
| Measurement | 3 iterations, 1 second each |
| Forks | 1 |
| Threads | 1 |

## 명령

```bash
./gradlew :bluetape4k-leader-core:jmh --no-configuration-cache
```

보고서 파일:

- `리더 코어/빌드/보고서/jmh/human.txt`
- `리더 코어/빌드/보고서/jmh/results.json`

## 결과

높을수록 처리량이 더 좋습니다. 평균 시간에는 낮을수록 좋습니다.

| Benchmark | Throughput (ops/us) | Average time (us/op) |
|---|---:|---:|
| HistoryRecorder.blockingNoopAcquireComplete | 7.459 ± 0.632 | 0.137 ± 0.018 |
| HistoryRecorder.blockingInMemoryAcquireComplete | 5.643 ± 0.537 | 0.180 ± 0.035 |
| HistoryRecorder.suspendNoopAcquireComplete | 5.775 ± 0.641 | 0.172 ± 0.017 |
| HistoryRecorder.suspendInMemoryAcquireComplete | 4.577 ± 0.213 | 0.218 ± 0.047 |
| LocalLeader.blockingRunIfLeader | 2.208 ± 0.056 | 0.451 ± 0.123 |
| LocalLeader.completableFutureRunIfLeader | 2.209 ± 0.629 | 0.452 ± 0.146 |
| LocalLeader.asyncOnlyRunIfLeader | 2.194 ± 0.662 | 0.459 ± 0.232 |
| LocalLeader.suspendRunIfLeader | 0.787 ± 0.566 | 1.247 ± 0.184 |
| LocalLeader.virtualThreadRunIfLeader | 0.140 ± 0.006 | 7.018 ± 1.371 |

## 관찰

- 블로킹 및 직접 실행기 'CompletableFuture' 로컬 경로는 이 경합이 없는 시나리오에서 대략 '0.45us/op'로 효과적으로 연결됩니다.
- 이 하네스에서는 'runBlocking'과 'Mutex' 획득/해제가 포함되어 있기 때문에 코루틴 로컬 선출이 더 느립니다.
- 모든 작업이 가상 스레드 실행기에 작업을 제출하고 완료될 때까지 기다리기 때문에 이 마이크로벤치에서는 가상 스레드 로컬 선출이 의도적으로 느려집니다.
- 인 메모리 내역 레코더 오버헤드는 블로킹 및 정지 픽스처 모두에서 '0.25us/op' 미만이며, 이는 인 메모리 기록을 위한 이전 1ms 핫 경로
- 목표보다 훨씬 낮습니다.

## 다음 작품

- 문제 #327은 Testcontainers를 사용하여 크로스 백엔드 벤치마크를 추가하고 핫 경로 획득/해제 비용과 별도의 잠금 백엔드 설정 비용을
- 추가해야 합니다.
- 문제 #328에서는 백엔드 결과를 비교할 수 있는 후에만 README 차트를 게시해야 합니다.
- 문제 #329는 위의 벤치마크 행과 문제 #327의 크로스 백엔드 결과에서 자체 개선 작업을 시작해야 합니다.
