# 문제 #414 MongoDB 벤치마크 반복 일시 중지 - 2026-06-05

문제 #414는 동일한 시스템의 Lettuce, Redisson 및 Hazelcast 일시 중지 기준에 대해 시끄러운 MongoDB 일시 중지 리더 선택 행을
반복했습니다. 목표는 튜닝 작업을 시작하기 전에 짧은 기간의 벤치마크 노이즈에서 반복 가능한 백엔드 오버헤드를 분리하는 것이었습니다.

동일한 기계 비교에만 이 보고서를 사용하십시오. 이는 릴리스 등급 성능 주장이 아닙니다.

## 주의 사항

- 'SuspendBackendLeaderElectorBenchmark.runIfLeader'만 측정되었습니다.
- 벤치마크에서는 기존 크로스 백엔드 기준과 동일한 포크 1개, 스레드 1개, 워밍업 2개, 1초 측정 반복 3회를 사용했습니다.
- 생성된 Gradle `benchmarkBenchmark` 및 `benchmarkAverageTimeBenchmark` 작업이 먼저 확인되었지만
- `--args`를 통한 런타임 필터링이 `kotlinx-benchmark` 실행기 구성 경로를 대체합니다. 따라서 집중 실행에서는
- `:benchmark:benchmarkBenchmarkJar`에서 생성된 공식 JVM 벤치마크 JAR을 사용했습니다.
- 각 행은 JMH 포크에서 자체 Testcontainer를 시작합니다. 컨테이너 시작은 측정된 반복 범위를 벗어나지만 Docker 및 로컬 리소스 압력은
- 여전히 ​​단기 점수에 영향을 미칠 수 있습니다.
- JMH는 GraalVM JDK 25에서 실행되었습니다. 왜냐하면 GraalVM JDK 25가 이 반복에 대한 활성 셸 `JAVA_HOME`이었기
- 때문입니다. 이전/이후 주장을 하기 전에 동일한 JDK와 비교하십시오.

## 환경

| Field | Value |
|---|---|
| Date | 2026-06-05 |
| Host | Apple M4 Pro, 12 CPUs, 48 GiB RAM |
| OS | macOS 26.5.1 arm64 |
| JDK | Oracle GraalVM 25.0.3 |
| Gradle | 9.5.1 |
| kotlinx-benchmark | 0.4.17 |
| JMH | 1.37 |
| Docker | Colima, Docker server 29.2.1, Ubuntu 24.04.4 LTS, 3905 MB |
| Warmup | 2 iterations, 1 second each |
| Measurement | 3 iterations, 1 second each |
| Forks | 1 |
| Threads | 1 |

## 명령

작업 이름 확인:

```bash
./gradlew :benchmark:tasks --all --no-daemon
```

JMH JAR 생성:

```bash
./gradlew :benchmark:benchmarkBenchmarkJar --no-daemon --no-configuration-cache --rerun-tasks
```

처리량 반복, 1에서 3까지 `run` 값당 한 번씩 실행:

```bash
java -jar benchmark/build/benchmarks/benchmark/jars/benchmark-benchmark-jmh-0.4.0-JMH.jar \
  -foe true -bm thrpt -tu s -wi 2 -i 3 -w 1s -r 1s -f 1 \
  -p backend=lettuce,redisson,mongo,hazelcast \
  -rf json -rff docs/benchmarks/2026-06-05-issue-414-mongodb-suspend-throughput-run-${run}.json \
  '.*SuspendBackendLeaderElectorBenchmark.runIfLeader.*'
```

평균 시간 반복, 1에서 3까지 `run` 값당 한 번 실행:

```bash
java -jar benchmark/build/benchmarks/benchmark/jars/benchmark-benchmark-jmh-0.4.0-JMH.jar \
  -foe true -bm avgt -tu us -wi 2 -i 3 -w 1s -r 1s -f 1 \
  -p backend=lettuce,redisson,mongo,hazelcast \
  -rf json -rff docs/benchmarks/2026-06-05-issue-414-mongodb-suspend-average-time-run-${run}.json \
  '.*SuspendBackendLeaderElectorBenchmark.runIfLeader.*'
```

기계가 읽을 수 있는 소스 아티팩트:

- [`2026-06-05-issue-414-mongodb-suspens-throughput-run-1.json`](./2026-06-05-issue-414-mongodb-suspens-throughput-run-1.json)
- [`2026-06-05-issue-414-mongodb-suspens-throughput-run-2.json`](./2026-06-05-issue-414-mongodb-suspens-throughput-run-2.json)
- [`2026-06-05-issue-414-mongodb-suspens-throughput-run-3.json`](./2026-06-05-issue-414-mongodb-suspens-throughput-run-3.json)
- [`2026-06-05-issue-414-mongodb-suspens-average-time-run-1.json`](./2026-06-05-issue-414-mongodb-suspens-average-time-run-1.json)
- [`2026-06-05-issue-414-mongodb-suspens-average-time-run-2.json`](./2026-06-05-issue-414-mongodb-suspens-average-time-run-2.json)
- [`2026-06-05-issue-414-mongodb-suspens-average-time-run-3.json`](./2026-06-05-issue-414-mongodb-suspens-average-time-run-3.json)

## 요약 반복

높을수록 처리량이 더 좋습니다. 평균 시간에는 낮을수록 좋습니다.

| Backend | Throughput mean (ops/s) | Throughput run range (ops/s) | Mean JMH error (ops/s) | Average-time mean (us/op) | Average-time run range (us/op) | Mean JMH error (us/op) |
|---|---:|---:|---:|---:|---:|---:|
| lettuce | 1,459.312 | 1,437.426 - 1,485.930 | 752.101 | 709.290 | 675.345 - 741.277 | 221.520 |
| redisson | 1,408.395 | 1,395.184 - 1,431.931 | 883.032 | 716.850 | 689.188 - 731.004 | 490.830 |
| hazelcast | 1,406.936 | 1,378.012 - 1,441.799 | 695.930 | 734.549 | 700.048 - 762.327 | 716.699 |
| mongo | 634.467 | 302.035 - 896.383 | 3,030.521 | 3,796.573 | 1,324.228 - 5,348.705 | 25,693.233 |

## 실행별 결과

### 처리량

| Run | Lettuce (ops/s) | Redisson (ops/s) | Hazelcast (ops/s) | MongoDB (ops/s) |
|---:|---:|---:|---:|---:|
| 1 | 1,485.930 ± 726.394 | 1,398.071 ± 511.196 | 1,378.012 ± 287.418 | 896.383 ± 754.808 |
| 2 | 1,454.580 ± 241.711 | 1,431.931 ± 797.288 | 1,400.998 ± 236.957 | 704.984 ± 3,923.388 |
| 3 | 1,437.426 ± 1,288.199 | 1,395.184 ± 1,340.612 | 1,441.799 ± 1,563.414 | 302.035 ± 4,413.367 |

### 평균 시간

| Run | Lettuce (us/op) | Redisson (us/op) | Hazelcast (us/op) | MongoDB (us/op) |
|---:|---:|---:|---:|---:|
| 1 | 711.246 ± 269.694 | 689.188 ± 145.261 | 741.271 ± 890.111 | 1,324.228 ± 2,903.123 |
| 2 | 675.345 ± 83.277 | 731.004 ± 751.351 | 762.327 ± 591.689 | 4,716.788 ± 39,107.223 |
| 3 | 741.277 ± 311.589 | 730.357 ± 575.876 | 700.048 ± 668.298 | 5,348.705 ± 35,069.352 |

## 결정

이번 반복에서는 프로덕션 튜닝 문제가 발생하지 않았습니다. MongoDB는 Redis 및 Hazelcast 정지 행보다 지속적으로 느렸지만 좁은 최적화 목표에
비해 점수와 오류 범위가 너무 넓었습니다.

- 처리량은 3회 반복에 걸쳐 896ops/s에서 302ops/s로 감소했습니다.
- 세 번의 반복에 걸쳐 평균 시간이 1.3ms/op에서 5.3ms/op로 이동했습니다.
- MongoDB에 대한 JMH 오류는 노이즈가 많은 반복에서 측정된 점수를 초과했습니다.

현재 MongoDB 일시 중지 행을 시끄러운 미리 보기-백엔드 비교 지점으로 처리합니다. 프로덕션 코드를 변경하기 전에 동일한 JDK, 더 많은 측정 시간 및
선택적 MongoDB/클라이언트 프로파일링을 사용하여 더 긴 프로필을 다시 실행하여 병목 현상을 격리할 수 있을 만큼 안정적으로 유지하세요.

## 확인

- `./gradlew :benchmark:tasks --all --no-daemon`
- `./gradlew :benchmark:benchmarkBenchmarkJar --no-daemon --no-configuration-cache --rerun-tasks`
- JMH 처리량 반복, 3회 실행, 위에 링크된 원시 JSON.
- JMH 평균 시간 반복, 3회 실행, 위에 링크된 원시 JSON.
