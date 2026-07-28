# 이슈 #523 기록 레코더 관찰 가능성 벤치마크

문제 #523은 `HistoryRecorderBenchmark`를 확장하여 레코더 전용 기록 경로에 명시적인 관찰 가능성 행이 있습니다. 벤치마크는 블로킹 및
suspend API, 터미널 상태 및 메타데이터 크기 전반에 걸쳐 무작동, 메모리 내 및 마이크로미터 래핑 기록 레코더를 비교합니다.

## 범위

- 레코더 구현: 무작동, 메모리 내, `SimpleMeterRegistry`를 갖춘 마이크로미터.
- 터미널 상태: 획득 + 완료, 획득 + 실패.
- API: `SafeLeaderHistoryRecorder`를 차단하고 `SuspendSafeLeaderHistoryRecorder`를 일시 중지합니다.
- 메타데이터 모드: '비어 있음', '소형', '대형'.

현재 이력 레코더 계약은 획득, 완료 및 실패한 이벤트를 기록합니다. 건너뛰거나 선택되지 않은 터미널 이벤트를 노출하지 않으므로 건너뛰기 상태 적용 범위는
문제 #521의 리더 경합 벤치마크에 남아 있습니다. 이러한 행은 레코더 전용 행입니다. 여기에는 Spring 조언 디스패치 또는 백엔드 잠금 획득이 포함되지
않습니다. 이슈 #522에서는 Spring 조언 오버헤드를 다루고 있습니다.

## 명령

이 모듈의 주요 Gradle 벤치마크 작업은 다음과 같습니다.

```bash
./gradlew :benchmark:benchmarkBenchmark :benchmark:benchmarkAverageTimeBenchmark --no-configuration-cache --rerun-tasks
```

이 문제의 경우 전체 Gradle 작업은 전체 벤치마크 제품군을 실행합니다. 따라서 원시 문제 증거는 생성된 JMH jar를 클래스 필터와 함께 사용하여 이
저장소의 기존 벤치마크 증거 패턴과 일치합니다.

```bash
./gradlew :benchmark:compileBenchmarkKotlin :benchmark:benchmarkBenchmarkJar --no-daemon --no-configuration-cache --console=plain

java -jar benchmark/build/benchmarks/benchmark/jars/benchmark-benchmark-jmh-0.5.0-JMH.jar \
  '.*HistoryRecorderBenchmark.*' \
  -bm thrpt -tu s -f 1 -wi 1 -i 2 -w 500ms -r 500ms \
  -rf json -rff docs/benchmarks/2026-07-02-issue-523-history-recorder-throughput.json

java -jar benchmark/build/benchmarks/benchmark/jars/benchmark-benchmark-jmh-0.5.0-JMH.jar \
  '.*HistoryRecorderBenchmark.*' \
  -bm avgt -tu us -f 1 -wi 1 -i 2 -w 500ms -r 500ms \
  -rf json -rff docs/benchmarks/2026-07-02-issue-523-history-recorder-average-time.json
```

실행 형태: 포크 1개, 스레드 1개, 500ms 워밍업 반복 1회, 500ms 측정 반복 2회. 이를 릴리스 등급 성능 주장이 아닌 동일한 시스템과 비교할
수 있는 스냅샷으로 사용하세요.

Raw data:

- [`2026-07-02-issue-523-history-recorder-throughput.json`](./2026-07-02-issue-523-history-recorder-throughput.json)
- [`2026-07-02-issue-523-history-recorder-average-time.json`](./2026-07-02-issue-523-history-recorder-average-time.json)

Charts:

- [`leader-history-observability-throughput-chart-01.svg`](../images/readme-charts/leader-history-observability-throughput-chart-01.svg)
- [`leader-history-observability-latency-chart-01.svg`](../images/readme-charts/leader-history-observability-latency-chart-01.svg)

## 결과

높을수록 처리량이 더 좋습니다. 평균 시간에는 낮을수록 좋습니다.

| API | Recorder | Terminal | Metadata | Throughput (ops/s) | Average time (us/op) |
|---|---|---|---|---:|---:|
| Blocking | Noop | Completed | empty | 369,591,201 | 0.0026 |
| Blocking | Noop | Completed | small | 62,153,981 | 0.0167 |
| Blocking | Noop | Completed | large | 3,951,395 | 0.2205 |
| Blocking | Noop | Failed | empty | 48,523,487 | 0.0180 |
| Blocking | Noop | Failed | small | 30,506,848 | 0.0323 |
| Blocking | Noop | Failed | large | 4,036,253 | 0.2597 |
| Blocking | In-memory | Completed | empty | 57,326,961 | 0.0182 |
| Blocking | In-memory | Completed | small | 18,875,312 | 0.0545 |
| Blocking | In-memory | Completed | large | 3,182,919 | 0.3123 |
| Blocking | In-memory | Failed | empty | 29,559,171 | 0.0343 |
| Blocking | In-memory | Failed | small | 14,255,133 | 0.0715 |
| Blocking | In-memory | Failed | large | 2,861,895 | 0.3491 |
| Blocking | Micrometer | Completed | empty | 53,053,805 | 0.0183 |
| Blocking | Micrometer | Completed | small | 18,031,147 | 0.0570 |
| Blocking | Micrometer | Completed | large | 3,160,459 | 0.3175 |
| Blocking | Micrometer | Failed | empty | 30,312,777 | 0.0331 |
| Blocking | Micrometer | Failed | small | 13,910,688 | 0.0709 |
| Blocking | Micrometer | Failed | large | 2,820,083 | 0.3946 |
| Suspend | Noop | Completed | empty | 33,042,164 | 0.0324 |
| Suspend | Noop | Completed | small | 23,477,969 | 0.0426 |
| Suspend | Noop | Completed | large | 4,000,090 | 0.2571 |
| Suspend | Noop | Failed | empty | 20,552,592 | 0.0446 |
| Suspend | Noop | Failed | small | 16,906,301 | 0.0635 |
| Suspend | Noop | Failed | large | 3,493,689 | 0.2834 |
| Suspend | In-memory | Completed | empty | 21,762,347 | 0.0461 |
| Suspend | In-memory | Completed | small | 11,173,330 | 0.0860 |
| Suspend | In-memory | Completed | large | 2,507,575 | 0.3637 |
| Suspend | In-memory | Failed | empty | 14,170,744 | 0.0725 |
| Suspend | In-memory | Failed | small | 10,270,385 | 0.1035 |
| Suspend | In-memory | Failed | large | 2,557,119 | 0.3860 |
| Suspend | Micrometer | Completed | empty | 21,581,894 | 0.0464 |
| Suspend | Micrometer | Completed | small | 11,671,342 | 0.0878 |
| Suspend | Micrometer | Completed | large | 2,786,518 | 0.3865 |
| Suspend | Micrometer | Failed | empty | 13,528,362 | 0.0739 |
| Suspend | Micrometer | Failed | small | 9,551,932 | 0.1031 |
| Suspend | Micrometer | Failed | large | 2,516,993 | 0.3844 |

## 해석

메타데이터 크기는 레코더 전용 경로를 지배합니다. 무작동 차단 완료 행은 빈 메타데이터가 있는 0.0026 us/op에서 큰 메타데이터가 있는 0.2205
us/op로 이동합니다. 안전한 레코더가 레코드를 싱크에 전달하기 전에 여전히 레코드를 삭제하기 때문입니다. 인 메모리 행과 마이크로미터 행은 동일한 모양을
보여줍니다. 즉, 큰 메타데이터 비용이 카운터 데코레이터 자체보다 훨씬 더 눈에 띕니다.

작은 메타데이터의 경우 Micrometer 래퍼는 완성된 경로의 메모리 내 레코더에 가깝습니다. 완료된 행 차단은 인메모리의 경우 18.9M ops/s이고
마이크로미터의 경우 18.0M ops/s입니다. 일시 중단 완료된 행은 이 단기 실행에서 각각 11.2M ops/s 및 11.7M ops/s입니다. 정지 완료
행의 작은 반전을 마이크로미터가 경로를 개선한다는 증거가 아니라 실행 노이즈로 처리합니다.

'recordFailed'는 예외 유형을 추출하고 메시지를 삭제/자르기 때문에 실패 행은 완료된 행보다 느립니다. 작은 메타데이터의 경우 인 메모리 차단은
0.0545 us/op에서 0.0715 us/op로 이동하고, 인 메모리 일시 중단은 0.0860 us/op에서 0.1035 us/op로 이동합니다.

벤치마크에서는 'SimpleMeterRegistry'만 사용합니다. 외부 메트릭 백엔드, 히스토그램 게시, 푸시 레지스트리, 스크래핑 및 내보내기 I/O는 이
로컬 레코더 전용 벤치마크 외부에 남아 있습니다.
