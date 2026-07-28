# Issue #522 Spring 리더 조언 벤치마크

이슈 #522는 직접 로컬 선거인 호출과 비교하여 `@LeaderElection` Spring AOP 조언에 의해 추가된 프레임워크 오버헤드를 측정합니다.
픽스쳐는 로컬 블로킹 및 suspend 선출기를 사용하므로 백엔드 I/O가 주석 조회, 인수 검사, SpEL 평가, 코루틴 연속 처리 및 선택적 AOP 레코더 디스패치를
​​숨기지 않습니다.

## 명령

```bash
./gradlew :benchmark:compileBenchmarkKotlin --no-daemon --no-configuration-cache --console=plain
./gradlew :benchmark:benchmarkBenchmarkJar --no-daemon --no-configuration-cache --console=plain

JAR=benchmark/build/benchmarks/benchmark/jars/benchmark-benchmark-jmh-0.5.0-JMH.jar

java -jar "$JAR" 'io\.bluetape4k\.leader\.benchmark\.SpringLeaderAdviceBenchmark\..*' \
  -p instrumentation=none,noop \
  -bm thrpt -tu s -f 1 -wi 1 -i 2 -r 500ms \
  -rf json -rff docs/benchmarks/2026-07-02-issue-522-spring-advice-throughput.json

java -jar "$JAR" 'io\.bluetape4k\.leader\.benchmark\.SpringLeaderAdviceBenchmark\..*' \
  -p instrumentation=none,noop \
  -bm avgt -tu us -f 1 -wi 1 -i 2 -r 500ms \
  -rf json -rff docs/benchmarks/2026-07-02-issue-522-spring-advice-average-time.json
```

## 차트

![스프링 조언 처리량](../images/readme-charts/leader-spring-advice-throughput-chart-01.png)

![스프링 조언 대기 시간](../images/readme-charts/leader-spring-advice-latency-chart-01.png)

## 결과

| Benchmark | Instrumentation | Throughput (ops/s) | Average time (us/op) |
|---|---:|---:|---:|
| direct sync | none | 2,255,925 | 0.44 |
| direct sync | noop | 2,258,324 | 0.44 |
| advice sync static | none | 1,718,542 | 0.56 |
| advice sync static | noop | 1,691,326 | 0.58 |
| advice sync SpEL | none | 1,034,113 | 0.94 |
| advice sync SpEL | noop | 1,030,415 | 0.99 |
| direct suspend | none | 28,987,468 | 0.035 |
| direct suspend | noop | 29,639,156 | 0.036 |
| advice suspend static | none | 544,163 | 1.70 |
| advice suspend static | noop | 592,620 | 1.66 |
| advice suspend SpEL | none | 449,808 | 2.21 |
| advice suspend SpEL | noop | 451,542 | 2.25 |

## 해석

블로킹 정적 이름 조언 경로는 직접 로컬 선출기 기준에 대해 주석 메타데이터 조회, AspectJ 조인 포인트 디스패치, Bean 선택 및 레코더 반복과 같은
소량의 프레임워크 작업을 추가합니다. SpEL 행은 호출할 때마다 메서드 인수에 대해 주석이 달린 표현식을 평가하기 때문에 속도가 더 느립니다.

일시 중지 직접 기준선은 이 로컬 픽스처에서 의도적으로 매우 작으므로 Spring 조언과의 상대적 격차가 커 보입니다. 절대적인 조언 비용은 여전히 ​​낮은
마이크로초입니다. 이 단기적으로 정적 일시 중지 조언은 약 1.7 us/op이고 SpEL 일시 중지 조언은 약 2.2 us/op입니다. 이는 일반적으로 잠금
I/O가 지배적인 실제 분산 백엔드 주변에서 주석 조언이 허용되는지 여부를 결정하는 데 유용한 숫자입니다.

`instrumentation=noop`은 무작동 AOP 측정항목 레코더를 설치합니다. 여기서 측정된 모양은 실질적으로 변경되지 않습니다. 작은 차이는 짧은
JMH 실행 소음 내에 있습니다. 실제 Micrometer 레코더는 의도적으로 별도의 메트릭 오버헤드 문제에 맡겨져 있으므로 이 장치는 조언 전달 및 표현
평가에 계속 초점을 맞춥니다.

Raw data:

- [`2026-07-02-issue-522-spring-advice-throughput.json`](2026-07-02-issue-522-spring-advice-throughput.json)
- [`2026-07-02-issue-522-spring-advice-average-time.json`](2026-07-02-issue-522-spring-advice-average-time.json)
