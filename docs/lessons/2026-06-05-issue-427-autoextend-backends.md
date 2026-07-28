# 문제 427 AutoExtend 백엔드 벤치마크

## 맥락

Issue #427에서는 Redis 적용 범위가 #422에 도달한 후 README 지원 단일 리더 `autoExtend` 백엔드 전체에 대한 벤치마크 적용 범위를 요청했습니다.

## 결정 또는 발견

Redis 빠른 및 갱신 기간 벤치마크 형태를 반영하는 집중된 로컬 및 MongoDB 일반 대 `autoExtend` 행을 추가합니다. 이 문제에서 Redis를 다시 측정하지 마십시오.

## 결과

이제 벤치마크 모듈에는 Local/MongoDB 자동 확장 행이 블로킹 및 일시 중단됩니다. 새로운 원시 JSON 및 해석 보고서는 `docs/benchmarks/2026-06-05-issue-427-autoextend-backends.md`에 보존됩니다.

MongoDB 짧은 창 행에는 여전히 광범위한 오류 범위가 있는 반면 로컬 빠른 행은 주로 감시 스케줄러 시작/닫기 오버헤드를 노출하므로 프로덕션 최적화 문제가 발생하지 않았습니다.

## 검증

- `./gradlew :benchmark:tasks --all --no-daemon`
- `./gradlew :benchmark:compileBenchmarkKotlin --no-daemon --no-configuration-cache`
- `./gradlew :benchmark:benchmarkBenchmarkJar --no-daemon --no-configuration-cache --rerun-tasks`
- `.*AutoExtendBackendLeaderElectorBenchmark.*`에 대해 집중된 JMH 처리량 및 평균 시간이 실행됩니다.

## 향후 지침

Redis 리스 연장 결과를 #422에 연결해 유지합니다. 향후 자동 확장 벤치마크 격차에 대비해 먼저 README/공용 API가 지원되는 백엔드 또는 옵션 조합을 문서화하는지 검증하세요. 지원되지 않는 그룹 선출 자동 확장에는 벤치마크 행 이전에 API/지원 작업이 필요합니다.
