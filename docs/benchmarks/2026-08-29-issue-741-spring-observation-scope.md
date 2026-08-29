# 이슈 #741 Spring 관측 범위 성능 검증 - 2026-08-29

이 보고서는 Spring `ObservationRegistry`별 lease-extension 자동 관측 범위를 추가한 뒤 기존 Spring advice hot path와 scope mismatch 경로의 성능·할당 영향을 같은 머신에서 비교한 결과다. 릴리스 등급 성능 보증이 아니라 이슈 #741의 15% 회귀 한도를 판정하기 위한 재현 가능한 증거다.

## 판정

- 기존 `SpringLeaderAdviceBenchmark.adviceSyncStaticName` 대비 처리량 중앙값은 **1.335% 개선**됐다.
- 같은 행의 평균 시간 중앙값 증가는 **0.023%**였다.
- 둘 다 승인된 회귀 한도 **15% 이하**이므로 `PASS`다.
- 실제 USER extension scoped mismatch는 no-observer보다 `-8.683 B/op`로 측정됐다.
- 실제 watchdog scoped mismatch는 no-observer보다 `-15.533 B/op`로 측정됐다. mismatch 때문에 event/context/timer allocation이 추가됐다는 증거는 없었다.

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
| 측정 직후 load average | 3.47 / 3.75 / 3.77 |

## 비교 대상

| 구분 | Git SHA | 설명 |
|---|---|---|
| baseline | `4c0d1156c268cde6191f6901c933b60ae6b92cff` | PR 생성 직전 `origin/develop` 기준 |
| candidate | `575cccdc505284528eea5bcf645d0b2e8b62124b` | 구현·문서와 정상 active USER benchmark를 포함한 측정 대상 |

최종 delivery head는 이 결과 문서를 갱신한 candidate의 descendant다. 측정한 실행 코드는 `575cccdc505284528eea5bcf645d0b2e8b62124b`와 동일하다.

## 명령

baseline과 candidate의 각 worktree에서 benchmark JAR를 만든 뒤 다음 행을 같은 옵션으로 실행했다.

```bash
./gradlew :benchmark:benchmarkBenchmarkJar --no-daemon --no-configuration-cache --rerun-tasks

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
  -rf json -rff benchmark/build/reports/benchmarks/issue741/candidate-575ccdc-scope-allocation.json
```

로컬 원본 JSON:

- baseline worktree `benchmark/build/reports/benchmarks/issue741/baseline-4c0d115-throughput.json`
- baseline worktree `benchmark/build/reports/benchmarks/issue741/baseline-4c0d115-average-time.json`
- candidate worktree `benchmark/build/reports/benchmarks/issue741/candidate-575ccdc-throughput.json`
- candidate worktree `benchmark/build/reports/benchmarks/issue741/candidate-575ccdc-average-time.json`
- candidate worktree `benchmark/build/reports/benchmarks/issue741/candidate-575ccdc-scope-allocation.json`

JSON은 Gradle build output으로 보존되며 커밋하지 않는다. 위 명령과 exact SHA가 재생성 기준이다.

## 기존 Spring advice 비교

각 값은 fork별 측정 평균 3개의 중앙값이다. 처리량은 높을수록, 평균 시간은 낮을수록 좋다.

| 모드 | baseline 중앙값 | candidate 중앙값 | 회귀율 | 한도 | 판정 |
|---|---:|---:|---:|---:|---|
| Throughput | 1,538,565.461 ops/s | 1,559,104.688 ops/s | -1.335% (개선) | 15% | PASS |
| Average time | 0.645029 us/op | 0.645176 us/op | 0.023% | 15% | PASS |

참고로 전체 9회 측정에서 계산된 JMH score와 99.9% 신뢰 오차는 throughput baseline `1,541,977.343 ± 23,227.277 ops/s`, candidate `1,558,809.965 ± 16,710.785 ops/s`; average time baseline `0.644943 ± 0.012040 us/op`, candidate `0.645219 ± 0.003962 us/op`이었다.

## Scope mismatch allocation

| 경로 | no-observer | scoped-mismatch | 차이 | 해석 |
|---|---:|---:|---:|---|
| `USER blocking` | 1,031.566 ± 15.531 B/op | 1,022.882 ± 17.417 B/op | -8.683 B/op | mismatch 추가 할당 없음 |
| `WATCHDOG blocking` | 1,963.116 ± 28.137 B/op | 1,947.582 ± 10.947 B/op | -15.533 B/op | mismatch 추가 할당 없음 |

`scoped-mismatch`는 일치하는 observer가 없을 때 `hasObservers(scope)`에서 빠져나가므로 lease-extension event, 관측 context, timer를 만들지 않는다. 이 표의 총 `B/op`는 benchmark harness와 실제 lease/watchdog 실행 비용까지 포함하며, 오차 구간 밖의 양의 증분은 관찰되지 않았다.

## 결론

registry별 관측 범위 분리는 기존 Spring advice 경로의 15% 성능 한도를 충족한다. 자동 observer와 scope가 불일치하는 경로에서도 event/context/timer 추가 할당은 확인되지 않았다. 회귀 판정은 같은 머신, 같은 JDK, exact baseline/candidate SHA의 3-fork 결과에 한정한다.
