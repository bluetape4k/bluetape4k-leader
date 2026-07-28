# 이슈 #422 Redis 리스 연장 벤치마크

## 맥락

Milestone 0.3.0에는 Lettuce 및 Redisson 리스 연장 동작을 위해 집중된 Redis 벤치마크 행이 필요했습니다. 이 문제에서는 Redisson 감시 동작을 언급했지만 현재 Redisson 선출기는 항상 명시적인 `leaseTime`를 전달합니다.

## 결정

오늘날 존재하는 공개 동작(정상 실행과 공유 bluetape4k `autoExtend` 경로)을 벤치마킹합니다. 별도의 설계와 증거 없이 프로덕션 감시 동작이나 최적화를 추가하지 마세요.

## 결과

Redis 리스 연장 벤치마크 클래스 블로킹 및 일시 중단을 추가하고 원시 처리량 및 평균 시간 JSON 아티팩트를 저장했으며 벤치마크 README/보고서에 Redisson 감시 주의 사항을 문서화했습니다.

## 검증

- `./gradlew :benchmark:compileBenchmarkKotlin --no-daemon`
- `./gradlew :benchmark:benchmarkBenchmark :benchmark:benchmarkAverageTimeBenchmark --no-daemon --no-configuration-cache --rerun-tasks`

## 미래의 규칙

기본 Redisson 감시 행이 다시 요청되면 먼저 감시 획득 경로를 명시적으로 노출하거나 설계하세요. 공유 `autoExtend` 행에 기본 Redisson 감시 증거로 레이블을 지정하지 마세요.
