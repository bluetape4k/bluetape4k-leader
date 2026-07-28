# 이슈 #327 리더 크로스 백엔드 벤치마크

## 맥락

리더의 자체 개선 작업에는 로컬, Redis, SQL, MongoDB, Hazelcast 및 ZooKeeper 구현 전반에 걸쳐 비교 가능한 기준 데이터가 필요했습니다. 벤치마크의 작업 공간 표준은 JVM 백엔드로 JMH를 사용하는 `kotlinx-benchmark`입니다.

## 결정

`src/benchmark/kotlin`, `benchmarkImplementation`, 명시적 `JvmBenchmarkTarget.jmhVersion`, INFO 로깅 및 두 개의 JSON 프로필(처리량 및 평균 시간)을 사용하여 게시되지 않은 중앙 `benchmark/` 모듈을 만들었습니다. 벤치마크 클래스 경로는 모든 테스트 또는 컴파일 전용 종속성을 상속하는 대신 의도적으로 명시적입니다. 이전 #326 로컬 벤치마크 클래스가 `leader-core/src/jmh`에서 이 모듈로 이동되었습니다.

## 결과

`./gradlew :benchmark:benchmarkBenchmark :benchmark:benchmarkAverageTimeBenchmark --no-configuration-cache --rerun-tasks`는 설정 연기 검사가 추가된 후 포크 1을 사용하여 5분 18초 만에 성공적으로 완료되었습니다. 결과는 `docs/benchmarks/2026-05-21-leader-cross-backend-baseline.md`에 기록됩니다.

## 검증

- `./gradlew :benchmark:compileBenchmarkKotlin --no-configuration-cache`
- `./gradlew :benchmark:benchmarkBenchmark :benchmark:benchmarkAverageTimeBenchmark --no-configuration-cache --rerun-tasks`
- `actionlint .github/workflows/ci.yml`
- `git diff --check`

## 향후 지침

- bluetape4k 벤치마크 작업의 경우 직접 JMH Gradle 플러그인을 추가하는 대신 설정된 `kotlinx-benchmark` 소스 세트 패턴에서 시작하세요.
- 처리량을 기본 순위 지표로 유지하고 평균 시간을 보조 대기 시간 증거로 유지합니다.
- 인프라가 연결되지 않은 경우 조기에 설정을 실패하고 설정 스모크 체크를 유지하여 벤치마크가 건너뛴 리더 경로를 자동으로 측정하지 않도록 합니다.
- H2 행을 분산 백엔드 클레임으로 비교하지 마세요. 로컬 SQL 계층 오버헤드를 측정합니다.
- 최적화 결정을 위해 MongoDB 행을 사용하기 전에 MongoDB 측정을 반복하거나 프로필을 늘립니다.
