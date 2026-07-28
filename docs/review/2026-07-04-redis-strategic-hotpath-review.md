# Redis 전략적 핫 패스 7계층 검토

날짜: 2026-07-04 범위: Issue #574, 마일스톤 0.5.0

## 검토된 모듈

- `leader-core`: 로컬 전략 선거인 및 디버그 전용 점수 형식화.
- `leader-redis-lettuce`: 전략적 후보 레지스트리 조회 경로입니다.
- `leader-redis-redisson`: 전략적 선택자 및 벤치마크 참조 백엔드.
- `benchmark`: Redis 전략적 후보 조회 벤치마크 범위.

## 7계층 결과

1. 정확성: 통과
   - Lettuce 전략적 후보는 정확한 후보 키와 잠금 범위 노드 ID 인덱스에 등록됩니다.
   - 목록은 누락되거나 오래된 페이로드를 필터링하고 인덱스에서 오래된 노드 ID를 제거합니다.
   - 선거 의미론은 여전히 동일한 `ElectionStrategy` 입력 및 승자 검증을 사용합니다.

2. API 및 계약 호환성: 통과
   - 공개 전략 선거인 API는 변경되지 않았습니다.
   - Redis 키 파생은 후보 레지스트리 구현 내부에 남아 있습니다.

3. 동시성 및 취소: PASS
   - 차단 및 일시 중지 레지스트리는 동일한 인덱스 모양을 사용합니다.
   - 획득, 릴리스, 감시, 코루틴 취소 또는 소유자 정리 계약이 변경되지 않았습니다.

4. 백엔드 소유권 안전성: 통과
   - Lettuce는 전략적 후보 목록을 위해 더 이상 Redis 키스페이스 패턴을 검색하지 않습니다.
   - 조회는 잠금 범위 인덱스의 `SMEMBERS`와 정확한 후보 키 읽기로 제한됩니다.

5. 테스트 및 벤치마크: 통과
   - 기존 코어, Lettuce 및 Redisson 전략 테스트를 통과했습니다.
   - Lettuce 및 Redisson 전반에 걸쳐 `listCandidates` 및 `runScoredElection`에 대한 JMH 벤치마크 행을 추가했습니다.
   - 문제 증거에 대한 로컬 JMH 연기 JSON 결과를 캡처했습니다.

6. 보안 및 관찰 가능성: 통과
   - 자격 증명, 토큰 또는 백엔드 비밀 로깅은 변경되지 않습니다.
   - 이제 전략적 점수 형식 지정이 `log.debug {}` 람다 내에서 발생하므로 디버그 로깅이 비활성화된 경우 즉시 형식 지정이 방지됩니다.

7. 유지보수성: 합격
   - Lettuce 차단 및 일시 중지 레지스트리는 동일한 키/인덱스 체계를 공유합니다.
   - 벤치마크 적용 범위는 향후 회귀를 위해 Redis 전략적 조회 경로를 계속 표시합니다.

## 검증 증거

- `./gradlew :bluetape4k-leader-core:compileKotlin :bluetape4k-leader-core:compileTestKotlin :bluetape4k-leader-redis-lettuce:compileKotlin :bluetape4k-leader-redis-lettuce:compileTestKotlin :bluetape4k-leader-redis-redisson:compileKotlin :bluetape4k-leader-redis-redisson:compileTestKotlin :benchmark:compileBenchmarkKotlin --warning-mode all`
- `./gradlew :bluetape4k-leader-core:test --tests 'io.bluetape4k.leader.local.LocalStrategicLeaderElectorTest' --tests 'io.bluetape4k.leader.local.LocalStrategicSuspendLeaderElectorTest' :bluetape4k-leader-redis-lettuce:test --tests 'io.bluetape4k.leader.lettuce.LettuceStrategicLeaderElectorTest' --tests 'io.bluetape4k.leader.lettuce.LettuceStrategicSuspendLeaderElectorTest' :bluetape4k-leader-redis-redisson:test --tests 'io.bluetape4k.leader.redisson.RedissonStrategicLeaderElectorTest' --tests 'io.bluetape4k.leader.redisson.RedissonStrategicSuspendLeaderElectorTest' --warning-mode all`
- `./gradlew :benchmark:compileBenchmarkKotlin :benchmark:benchmarkBenchmarkJar --no-daemon --no-configuration-cache --console=plain`
- `java -jar benchmark/build/benchmarks/benchmark/jars/benchmark-benchmark-jmh-0.5.0-JMH.jar '.*RedisStrategicCandidateLookupBenchmark.*' -p candidateCount=16 -bm avgt -tu us -f 1 -wi 0 -i 1 -r 200ms -w 100ms -rf json -rff docs/benchmarks/2026-07-04-issue-574-redis-strategic-lookup-smoke.json`
- `rg -n "ScanArgs|ScanCursor|sync\\.scan|cmds\\.scan" leader-core/src/main leader-redis-lettuce/src/main leader-redis-redisson/src/main benchmark/src/benchmark -g '*.kt'`
- `git diff --check`

## Deferred 검증

전체 저장소 테스트는 요청된 워크플로우에 따라 전체 스택 이슈 트레인이 구현될 때까지 의도적으로 연기됩니다.
