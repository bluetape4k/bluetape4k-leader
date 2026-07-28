# 문제 #427 autoExtend 백엔드 벤치마크 - 2026-06-05

문제 #427은 ​​#422: 로컬 및 MongoDB에서 다루는 Redis 슬라이스 외부의 나머지 README 문서화 단일 리더 `autoExtend`
백엔드에 초점을 맞춘 `kotlinx-benchmark` 행을 추가합니다. 벤치마크 코드는 일반 `benchmark` 소스 세트에 있으며 측정된 아티팩트는
Gradle이 생성한 JMH jar 작업에 의해 구축되었습니다.

동일한 기계 비교에만 이 보고서를 사용하십시오. 이는 릴리스 등급 성능 주장이 아닙니다.

## 주의 사항

- Redis `autoExtend` 적용 범위는
- [`2026-06-01-issue-422-redis-lease-extension.md`](./2026-06-01-issue-422-redis-lease-extension.md)에
- 유지됩니다.
- 일반 'runIfLeader' 행은 60초 임대와 빠른 작업을 사용하여 정상적인 실행과 'autoExtend' 활성화 오버헤드를 측정합니다.
- 'runIfLeaderWithRenewalWindow' 행은 90ms 임대 및 45ms 작업 유지를 사용하므로 자동 확장 경로에 갱신 기간이 있습니다.
- 체류 시간이 지배적이므로 동일한 방법 내에서만 이러한 행을 비교하십시오.
- MongoDB 행은 리포지토리 Testcontainers 실행 프로그램을 사용합니다. Short-window MongoDB 점수는 특히 일시 중단된
- 빠른 행의 경우 광범위한 오류 범위를 가지므로 자체적으로 프로덕션 튜닝 문제를 정당화하지 않습니다.
- 생성된 Gradle 벤치마크 작업은 전체 벤치마크 매트릭스를 실행합니다. 이 실행에서는 Gradle을 사용하여 공식 JMH jar를 빌드한 다음 JMH
- 포함 패턴을 사용하여 원시 출력을 새로운 #427 행에 집중하도록 유지했습니다.
- 그룹 선택 자동 확장이 아직 지원되지 않기 때문에 `@LeaderGroupElection`은 벤치마킹되지 않습니다. 단일 리더 `autoExtend`를
- 지원하는 것으로 README에 문서화되지 않은 백엔드는 이 문제의 벤치마크 범위를 벗어납니다.

## 환경

| Field | Value |
|---|---|
| Date | 2026-06-05 |
| Host | Apple M4 Pro, 12 CPUs |
| OS | macOS 26.5.1 arm64 |
| JDK | Oracle GraalVM 25.0.3 |
| Gradle | 9.5.1 |
| Kotlin | 2.3.20 |
| kotlinx-benchmark | 0.4.17 |
| JMH | 1.37 |
| Docker | Testcontainers via `unix:///Users/debop/.colima/default/docker.sock`; server 29.2.1, Ubuntu 24.04.4 LTS, 3905 MB |
| Warmup | 2 iterations, 1 second each |
| Measurement | 3 iterations, 1 second each |
| Forks | 1 |
| Threads | 1 |

## 명령

```bash
./gradlew :benchmark:tasks --all --no-daemon
./gradlew :benchmark:compileBenchmarkKotlin --no-daemon --no-configuration-cache
./gradlew :benchmark:benchmarkBenchmarkJar --no-daemon --no-configuration-cache --rerun-tasks
java -jar benchmark/build/benchmarks/benchmark/jars/benchmark-benchmark-jmh-0.4.0-JMH.jar '.*AutoExtendBackendLeaderElectorBenchmark.*' -bm thrpt -tu s -f 1 -wi 2 -i 3 -w 1s -r 1s -rf json -rff docs/benchmarks/2026-06-05-issue-427-autoextend-backends-throughput.json
java -jar benchmark/build/benchmarks/benchmark/jars/benchmark-benchmark-jmh-0.4.0-JMH.jar '.*AutoExtendBackendLeaderElectorBenchmark.*' -bm avgt -tu us -f 1 -wi 2 -i 3 -w 1s -r 1s -rf json -rff docs/benchmarks/2026-06-05-issue-427-autoextend-backends-average-time.json
```

기계가 읽을 수 있는 소스 아티팩트:

- [`2026-06-05-issue-427-autoextend-backends-throughput.json`](./2026-06-05-issue-427-autoextend-backends-throughput.json)
- [`2026-06-05-issue-427-autoextend-backends-average-time.json`](./2026-06-05-issue-427-autoextend-backends-average-time.json)

## 로컬 및 MongoDB API 차단

높을수록 처리량이 더 좋습니다. 평균 시간에는 낮을수록 좋습니다.

| Scenario | Mode | Throughput (ops/s) | Average time (us/op) | Notes |
|---|---|---:|---:|---|
| `runIfLeader` | local-normal | 2,395,400.193 +/- 501,076.856 | 0.426 +/- 0.219 | 60s lease, fast action |
| `runIfLeader` | local-auto-extend | 805,517.783 +/- 1,278,895.802 | 1.237 +/- 2.269 | Shared watchdog start/close overhead visible |
| `runIfLeader` | mongo-normal | 971.090 +/- 544.247 | 5,774.991 +/- 28,639.740 | MongoDB Testcontainer |
| `runIfLeader` | mongo-auto-extend | 692.798 +/- 749.379 | 2,569.192 +/- 33,179.484 | Error bound too wide for tuning |
| `runIfLeaderWithRenewalWindow` | local-normal | 21.511 +/- 0.547 | 46,273.157 +/- 1,105.062 | 90ms lease, 45ms action dwell |
| `runIfLeaderWithRenewalWindow` | local-auto-extend | 21.577 +/- 3.122 | 46,154.705 +/- 2,389.850 | Dwell dominates |
| `runIfLeaderWithRenewalWindow` | mongo-normal | 16.198 +/- 2.870 | 57,592.652 +/- 14,277.831 | 90ms lease, 45ms action dwell |
| `runIfLeaderWithRenewalWindow` | mongo-auto-extend | 16.552 +/- 15.388 | 55,941.229 +/- 16,045.389 | Error bound overlaps normal row |

## 로컬 및 MongoDB API 일시중단

| Scenario | Mode | Throughput (ops/s) | Average time (us/op) | Notes |
|---|---|---:|---:|---|
| `runIfLeader` | local-normal | 868,702.969 +/- 143,615.007 | 1.168 +/- 0.429 | Coroutine local baseline |
| `runIfLeader` | local-auto-extend | 388,941.209 +/- 188,261.017 | 2.549 +/- 1.169 | Shared watchdog start/close overhead visible |
| `runIfLeader` | mongo-normal | 171.671 +/- 496.698 | 6,693.307 +/- 15,305.281 | Noisy MongoDB suspend row |
| `runIfLeader` | mongo-auto-extend | 240.190 +/- 2,241.840 | 5,954.376 +/- 37,242.530 | Error bound too wide for tuning |
| `runIfLeaderWithRenewalWindow` | local-normal | 21.496 +/- 0.945 | 46,579.372 +/- 1,339.338 | 90ms lease, 45ms action dwell |
| `runIfLeaderWithRenewalWindow` | local-auto-extend | 21.502 +/- 2.185 | 46,742.978 +/- 4,988.328 | Dwell dominates |
| `runIfLeaderWithRenewalWindow` | mongo-normal | 17.352 +/- 8.027 | 61,080.897 +/- 22,853.647 | 90ms lease, 45ms action dwell |
| `runIfLeaderWithRenewalWindow` | mongo-auto-extend | 17.678 +/- 5.739 | 55,882.592 +/- 11,014.145 | Error bound overlaps normal row |

## 결정

생산 최적화 후속 조치가 열리지 않았습니다. 새로운 행은 README 지원 로컬 및 MongoDB 단일 리더 'autoExtend'에 대한 벤치마크 적용 범위
격차를 줄이는 반면, 새로운 MongoDB 증거는 좁은 조정 문제에 비해 너무 복잡합니다. Redis는 #422의 적용을 받으며, 지원되지 않는 그룹 선택
또는 문서화되지 않은 백엔드 조합은 벤치마크 행을 추가하기 전에 별도의 API/지원 작업으로 추적해야 합니다.

## 확인

- `./gradlew :benchmark:tasks --all --no-daemon`
- `./gradlew :benchmark:compileBenchmarkKotlin --no-daemon --no-configuration-cache`
- `./gradlew :benchmark:benchmarkBenchmarkJar --no-daemon --no-configuration-cache --rerun-tasks`
- 집중된 JMH 처리량 실행은
- `docs/benchmarks/2026-06-05-issue-427-autoextend-backends-throughput.json`에 저장되었습니다.
- 집중된 JMH 평균 시간 실행이 `docs/benchmarks/2026-06-05-issue-427-autoextend-backends-average-time.json`에 저장되었습니다.
