# 문제 414 MongoDB 벤치마크 반복 일시 중지

## 맥락

일시 중단 MongoDB `runIfLeader` 벤치마크 행은 크로스 백엔드 기준에서 노이즈로 표시되었습니다. Issue #414에서는 튜닝 작업을 시작할지 여부를 결정하기 전에 동일한 컴퓨터에 대한 반복적인 증거를 요청했습니다.

## 결정 또는 발견

Lettuce, Redisson 및 Hazelcast에 대한 세 가지 집중 반복을 통해 MongoDB가 더 느리게 유지되지만 짧은 창 점수 범위와 JMH 오류가 좁은 생산 최적화 목표에 비해 너무 넓다는 것을 검증했습니다.

## 결과

프로덕션 코드가 변경되지 않았으며 후속 튜닝 문제도 열리지 않았습니다. 원시 처리량 및 평균 시간 JSON 파일과 결정 기록은 `docs/benchmarks/2026-06-05-issue-414-mongodb-suspend-repeat.md`에 보존됩니다.

## 검증

- `./gradlew :benchmark:tasks --all --no-daemon`
- `./gradlew :benchmark:benchmarkBenchmarkJar --no-daemon --no-configuration-cache --rerun-tasks`
- `lettuce,redisson,mongo,hazelcast`에 대한 JMH 처리량 반복, 3회 실행.
- `lettuce,redisson,mongo,hazelcast`에 대한 JMH 평균 시간 반복, 3회 실행.

## 향후 지침

집중적인 벤치마크 반복을 위해 생성된 kotlinx-benchmark 작업 이름을 먼저 검증하세요. 런타임 필터가 필요한 경우 Gradle를 사용하여 공식 벤치마크 JAR을 빌드하고 생성된 JavaExec 작업 대신 직접 JMH 실행이 사용되는 이유를 문서화하세요.
