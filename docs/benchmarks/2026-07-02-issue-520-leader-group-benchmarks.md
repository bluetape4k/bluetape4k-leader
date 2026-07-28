# Issue 520 리더 그룹 벤치마크 스냅샷

이슈 #520에는 그룹 세마포어 선택 경로에 대한 벤치마크 적용 범위가 추가되었습니다. 새로운 벤치마크 클래스는 `maxLeaders` 값 1, 2, 8을
사용하여 API 차단 및 정지를 다룹니다. 이 보고서는 README에 사용된 빠른 `maxLeaders=2` 차트 스냅샷을 기록합니다.

## 환경

- Date: 2026-07-02
- 호스트 OS: macOS 26.5.1, 빌드 25F80
- 자바: 오라클 GraalVM 21.0.11
- Gradle: 9.6.0
- Kotlin: 2.3.21
- 범위: 릴리스 등급 성능 데이터가 아닌 동일 머신 개발자 스냅샷

## 명령

벤치마크 소스가 먼저 컴파일되고 패키징되었습니다.

```bash
./gradlew :benchmark:compileBenchmarkKotlin --no-configuration-cache --console=plain --warning-mode all
./gradlew :benchmark:benchmarkBenchmarkJar --no-daemon --no-configuration-cache --console=plain --rerun-tasks
```

차트 스냅샷은 짧고 포크되지 않은 JMH 실행을 사용하여 Testcontainers 지원 백엔드를 동일한 프로세스에서 빠르게 비교할 수 있습니다.

```bash
java -jar benchmark/build/benchmarks/benchmark/jars/benchmark-benchmark-jmh-0.5.0-JMH.jar \
  '.*LeaderGroupElectorBenchmark\.(freeSlotRunIfLeader|saturatedSkipRunIfLeader|mixedRunIfLeader).*' \
  -p maxLeaders=2 -bm thrpt -tu s -f 0 -wi 0 -i 1 -r 200ms \
  -rf json -rff docs/benchmarks/2026-07-02-issue-520-leader-group-throughput.json

java -jar benchmark/build/benchmarks/benchmark/jars/benchmark-benchmark-jmh-0.5.0-JMH.jar \
  '.*LeaderGroupElectorBenchmark\.(freeSlotRunIfLeader|saturatedSkipRunIfLeader|mixedRunIfLeader).*' \
  -p maxLeaders=2 -bm avgt -tu us -f 0 -wi 0 -i 1 -r 200ms \
  -rf json -rff docs/benchmarks/2026-07-02-issue-520-leader-group-average-time.json
```

JMH는 '-f 0'이 디버깅 스타일 실행에만 적합하다고 경고합니다. 이 파일을 차트 및 연기 테스트 기록으로 취급하십시오. 프로덕션 튜닝 결정을 내리기 전에
분기되고 예열된 벤치마크를 사용하십시오.

## 원본 데이터

- 처리량 JSON:
- [`2026-07-02-issue-520-leader-group-throughput.json`](./2026-07-02-issue-520-leader-group-throughput.json)
- 평균 시간 JSON:
- [`2026-07-02-issue-520-leader-group-average-time.json`](./2026-07-02-issue-520-leader-group-average-time.json)
- 처리량 차트:
- [`leader-group-throughput-chart-01.svg`](../images/readme-charts/leader-group-throughput-chart-01.svg)
- /
- [`leader-group-throughput-chart-01.png`](../images/readme-charts/leader-group-throughput-chart-01.png)
- 평균 시간 차트:
- [`leader-group-latency-chart-01.svg`](../images/readme-charts/leader-group-latency-chart-01.svg)
- /
- [`leader-group-latency-chart-01.png`](../images/readme-charts/leader-group-latency-chart-01.png)

## 결과표

높을수록 처리량이 더 좋습니다. 평균 시간에는 낮을수록 좋습니다.

| API | Scenario | Backend | Throughput (ops/s) | Average time (us/op) |
|---|---|---|---:|---:|
| Blocking | Free slot | local | 541,782 | 2.11 |
| Blocking | Free slot | exposed-jdbc-h2 | 2,320 | 439.3 |
| Blocking | Free slot | lettuce | 806.3 | 1,010 |
| Blocking | Free slot | redisson | 902.4 | 1,017 |
| Blocking | Free slot | mongo | 344.5 | 3,370 |
| Blocking | Free slot | zookeeper | 209.2 | 4,605 |
| Blocking | Mixed slots | local | 1,482,781 | 0.67 |
| Blocking | Mixed slots | exposed-jdbc-h2 | 152.5 | 5,915 |
| Blocking | Mixed slots | lettuce | 1,543 | 2,029 |
| Blocking | Mixed slots | redisson | 963.4 | 989.9 |
| Blocking | Mixed slots | mongo | 75.87 | 13,146 |
| Blocking | Mixed slots | zookeeper | 247.5 | 4,745 |
| Blocking | Saturated skip | local | 38.2 | 26,036 |
| Blocking | Saturated skip | exposed-jdbc-h2 | 38.01 | 26,065 |
| Blocking | Saturated skip | lettuce | 35.89 | 27,693 |
| Blocking | Saturated skip | redisson | 36.36 | 27,597 |
| Blocking | Saturated skip | mongo | 35.2 | 27,234 |
| Blocking | Saturated skip | zookeeper | 32.88 | 29,086 |
| Suspend | Free slot | local | 216,124 | 4.33 |
| Suspend | Free slot | lettuce | 1,315 | 770.2 |
| Suspend | Free slot | redisson | 919.3 | 1,062 |
| Suspend | Free slot | mongo | 297 | 3,298 |
| Suspend | Free slot | zookeeper | 211.8 | 4,348 |
| Suspend | Mixed slots | local | 296,157 | 3.39 |
| Suspend | Mixed slots | lettuce | 1,396 | 684.1 |
| Suspend | Mixed slots | redisson | 1,104 | 971.9 |
| Suspend | Mixed slots | mongo | 82.79 | 10,747 |
| Suspend | Mixed slots | zookeeper | 276.7 | 4,017 |
| Suspend | Saturated skip | local | 38.04 | 26,269 |
| Suspend | Saturated skip | lettuce | 36.34 | 27,447 |
| Suspend | Saturated skip | redisson | 24.69 | 44,784 |
| Suspend | Saturated skip | mongo | 36.03 | 26,808 |
| Suspend | Saturated skip | zookeeper | 32.05 | 31,188 |

## 해석

- 로컬 및 차단 H2 행은 프레임워크/스토리지 형태 기준선입니다. 테이블에는 보존되어 있지만 원격 백엔드 차이점을 모호하게 하기 때문에 README
- 차트에서는 생략되었습니다.
- 포화 건너뛰기 행은 25ms 대기 시간 경로에 의해 지배되므로 모든 백엔드는 대기 창당 대략 하나의 작업 주위에 클러스터됩니다.
- Lettuce와 Redisson은 이 짧은 스냅샷에서 대부분의 사용 가능한 슬롯 및 혼합 슬롯 원격 행을 선도합니다. MongoDB는 혼합 슬롯의 경우
- 여전히 느리고 잡음이 많습니다.
- 차트는 로그 눈금을 사용하여 여유 슬롯 및 혼합 슬롯 행 옆에 포화된 행을 표시합니다.
