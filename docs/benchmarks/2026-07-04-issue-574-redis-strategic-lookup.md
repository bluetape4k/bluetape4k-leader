# 문제 #574 Redis 전략적 후보 조회 벤치마크

문제 #574는 Redis 전략적 선거인 후보 조회 핫 경로를 제한하고 Redis 지원 전략적 후보 목록 및 채점된 선거 실행에 대한 벤치마크 적용 범위를
추가합니다.

## 범위

- 백엔드: Lettuce 및 Redisson.
- 이 연기 스냅샷의 후보자 수: 16.
- 벤치마크:
  - `listCandidates`: 레지스트리 후보 조회 전용입니다.
  - `runScoredElection`: 후보 조회와 점수 전략 선택.

## 명령

```bash
./gradlew :benchmark:compileBenchmarkKotlin :benchmark:benchmarkBenchmarkJar --no-daemon --no-configuration-cache --console=plain

java -jar benchmark/build/benchmarks/benchmark/jars/benchmark-benchmark-jmh-0.5.0-JMH.jar \
  '.*RedisStrategicCandidateLookupBenchmark.*' \
  -p candidateCount=16 -bm avgt -tu us -f 1 -wi 0 -i 1 -r 200ms -w 100ms \
  -rf json -rff docs/benchmarks/2026-07-04-issue-574-redis-strategic-lookup-smoke.json
```

실행 형태: 포크 1개, 스레드 1개, 워밍업 반복 없음, 200ms 측정 반복 1회. 이를 릴리스 등급 성능 증거가 아닌 벤치마크 배선을 증명하고 현재 핫
경로 형태를 캡처하는 로컬 연기 지점으로 사용하십시오.

Raw data:

- [`2026-07-04-issue-574-redis-strategic-lookup-smoke.json`](./2026-07-04-issue-574-redis-strategic-lookup-smoke.json)

## 결과

평균 시간에는 낮을수록 좋습니다.

| Benchmark | Backend | Candidates | Average time (us/op) |
|---|---|---:|---:|
| `listCandidates` | Lettuce | 16 | 2,530.172 |
| `listCandidates` | Redisson | 16 | 1,518.235 |
| `runScoredElection` | Lettuce | 16 | 3,094.977 |
| `runScoredElection` | Redisson | 16 | 1,478.360 |

## 해석

Lettuce 후보 레지스트리는 전략적 후보 목록을 위해 더 이상 Redis 키스페이스 스캔을 수행하지 않습니다. 잠금 범위의 Redis 세트에 노드 ID를
저장하고 정확한 키로 후보 페이로드를 가져옵니다. 이는 관련되지 않은 Redis 키스페이스 크기 대신 잠금에 대한 후보 인덱스로 조회를 제한합니다.

Redisson은 이미 잠금 범위 맵을 사용했으며 백엔드 소유 후보 인덱스의 참조 형태로 남아 있습니다. 벤치마크는 Lettuce와 Redisson을 동일한
클래스로 유지하므로 향후 변경 사항은 동일한 JMH 매개변수를 사용하여 조회 및 선택 행을 비교할 수 있습니다.
