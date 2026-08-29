# 이슈 #741 Spring 관측 범위 성능 검증 - 2026-08-29

이 보고서는 Spring `ObservationRegistry`별 lease-extension 자동 관측 범위를 추가한 뒤 기존 Spring advice hot path와 scope mismatch 경로의 성능·할당 영향을 같은 머신에서 비교한 결과다. 릴리스 등급 성능 보증이 아니라 이슈 #741의 15% 회귀 한도를 판정하기 위한 재현 가능한 증거다.

## 판정

- 기존 `SpringLeaderAdviceBenchmark.adviceSyncStaticName` 대비 처리량 중앙값 저하는 **1.546%**였다.
- 같은 행의 평균 시간 중앙값 증가는 **0.179%**였다.
- 둘 다 승인된 회귀 한도 **15% 이하**이므로 `PASS`다.
- `USER` scoped mismatch의 `gc.alloc.rate.norm` 점수 차이는 `+8.011 B/op`이며 no-observer 결과의 JMH 오차 구간 안이다.
- 실제 watchdog scoped mismatch는 no-observer보다 `-13.729 B/op`로 측정됐다. mismatch 때문에 event/context/timer allocation이 추가됐다는 증거는 없었다.

## 환경

| 항목 | 값 |
|---|---|
| 측정일 | 2026-08-29 |
| 호스트 | Apple M4 Pro, 48 GiB RAM |
| OS | Darwin 25.6.0 arm64 |
| JDK | Oracle GraalVM 25.3.4.1, Java 25.0.4.1 LTS |
| Gradle | 9.7.0 |
| kotlinx-benchmark | 0.4.17 |
| JMH | 1.37 |
| 스레드 | 1 |
| 워밍업 | fork별 2회, 각 1초 |
| 측정 | fork별 3회, 각 1초 |
| Fork | 3 |
| 측정 직후 load average | 3.25 / 3.21 / 3.37 |

## 비교 대상

| 구분 | Git SHA | 설명 |
|---|---|---|
| baseline | `f44b7c69440f8ce5156185ce63209f523b2051fd` | `origin/develop`의 이슈 #741 변경 전 기준 |
| candidate | `dda4b43d22a8a583524b92b562b02c470b9b95ab` | 구현·문서·benchmark code를 포함한 측정 대상 |

최종 delivery head는 이 결과 문서를 추가한 candidate의 descendant다. 실행 코드는 `dda4b43d22a8a583524b92b562b02c470b9b95ab`와 동일하다.

## 명령

baseline과 candidate의 각 worktree에서 benchmark JAR를 만든 뒤 다음 행을 같은 옵션으로 실행했다.

```bash
./gradlew :benchmark:benchmarkBenchmark --no-daemon --no-configuration-cache --rerun-tasks

java -jar benchmark/build/benchmarks/benchmark/jars/benchmark-benchmark-jmh-1.0.0-JMH.jar \
  'io.bluetape4k.leader.benchmark.SpringLeaderAdviceBenchmark.adviceSyncStaticName' \
  -p instrumentation=none -bm thrpt -f 3 -wi 2 -i 3 -w 1s -r 1s -prof gc \
  -rf json -rff benchmark/build/reports/benchmarks/issue741/<baseline-or-candidate>-throughput.json

java -jar benchmark/build/benchmarks/benchmark/jars/benchmark-benchmark-jmh-1.0.0-JMH.jar \
  'io.bluetape4k.leader.benchmark.SpringLeaderAdviceBenchmark.adviceSyncStaticName' \
  -p instrumentation=none -bm avgt -tu us -f 3 -wi 2 -i 3 -w 1s -r 1s -prof gc \
  -rf json -rff benchmark/build/reports/benchmarks/issue741/<baseline-or-candidate>-average-time.json

java -jar benchmark/build/benchmarks/benchmark/jars/benchmark-benchmark-jmh-1.0.0-JMH.jar \
  'io.bluetape4k.leader.benchmark.LeaseExtensionObservationScopeBenchmark.(userBlocking|watchdogBlocking)' \
  -p observationMode=no-observer,scoped-mismatch -bm thrpt -f 3 -wi 2 -i 3 -w 1s -r 1s -prof gc \
  -rf json -rff benchmark/build/reports/benchmarks/issue741/candidate-dda4b43-scope-allocation.json
```

로컬 원본 JSON:

- `benchmark/build/reports/benchmarks/issue741/baseline-f44b7c6-throughput.json`
- `benchmark/build/reports/benchmarks/issue741/baseline-f44b7c6-average-time.json`
- `benchmark/build/reports/benchmarks/issue741/candidate-dda4b43-throughput.json`
- `benchmark/build/reports/benchmarks/issue741/candidate-dda4b43-average-time.json`
- `benchmark/build/reports/benchmarks/issue741/candidate-dda4b43-scope-allocation.json`

JSON은 Gradle build output으로 보존되며 커밋하지 않는다. 위 명령과 exact SHA가 재생성 기준이다.

## 기존 Spring advice 비교

각 값은 fork별 측정 평균 3개의 중앙값이다. 처리량은 높을수록, 평균 시간은 낮을수록 좋다.

| 모드 | baseline 중앙값 | candidate 중앙값 | 회귀율 | 한도 | 판정 |
|---|---:|---:|---:|---:|---|
| Throughput | 1,570,574.272 ops/s | 1,546,292.620 ops/s | 1.546% | 15% | PASS |
| Average time | 0.642208 us/op | 0.643356 us/op | 0.179% | 15% | PASS |

참고로 전체 9회 측정에서 계산된 JMH score와 99.9% 신뢰 오차는 throughput baseline `1,561,708.040 ± 28,898.393 ops/s`, candidate `1,548,839.914 ± 17,888.932 ops/s`; average time baseline `0.641733 ± 0.007320 us/op`, candidate `0.643375 ± 0.006187 us/op`이었다.

## Scope mismatch allocation

| 경로 | no-observer | scoped-mismatch | 차이 | 해석 |
|---|---:|---:|---:|---|
| `USER blocking` | 2,568.489 ± 20.153 B/op | 2,576.500 ± 0.012 B/op | +8.011 B/op | no-observer 오차 구간과 겹침 |
| `WATCHDOG blocking` | 1,959.735 ± 26.572 B/op | 1,946.006 ± 6.154 B/op | -13.729 B/op | mismatch 추가 할당 없음 |

`scoped-mismatch`는 일치하는 observer가 없을 때 `hasObservers(scope)`에서 빠져나가므로 lease-extension event, 관측 context, timer를 만들지 않는다. 이 표의 총 `B/op`는 benchmark harness와 실제 lease/watchdog 실행 비용까지 포함하며, 오차 구간 밖의 양의 증분은 관찰되지 않았다.

## 결론

registry별 관측 범위 분리는 기존 Spring advice 경로의 15% 성능 한도를 충족한다. 자동 observer와 scope가 불일치하는 경로에서도 event/context/timer 추가 할당은 확인되지 않았다. 회귀 판정은 같은 머신, 같은 JDK, exact baseline/candidate SHA의 3-fork 결과에 한정한다.
