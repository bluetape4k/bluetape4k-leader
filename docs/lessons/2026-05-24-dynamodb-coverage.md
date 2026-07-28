# DynamoDB 리더 테스트 범위

컨텍스트: Issue #366에서는 비동기 리더 API, 감시 임대 연장 및 그룹 취소 정리 일시 중지에 대한 DynamoDB 적용 범위가 누락된 것으로 검증되었습니다.

결정: 비동기 실행, 명시적 `autoExtend = true` 감시 동작 및 취소 정리를 위한 집중 통합 테스트를 추가합니다. 그룹 및 단일 리더 경합을 차단하기 위해 원시 실행기 기반 경합 검사를 `MultithreadingTester`로 대체합니다.

결과: DynamoDB 리더 테스트는 이제 이전에 누락된 동작을 다루며 접촉 경합 테스트에서 직접적인 `Executors`/`CountDownLatch` 사용을 방지합니다.

검증: `./gradlew :bluetape4k-leader-dynamodb:test --tests 'io.bluetape4k.leader.dynamodb.DynamoDbLeaderElectorIntegrationTest' --tests 'io.bluetape4k.leader.dynamodb.DynamoDbLeaderGroupElectorIntegrationTest' --tests 'io.bluetape4k.leader.dynamodb.DynamoDbSuspendLeaderGroupElectorIntegrationTest'`; `git diff --check`.

Future Guard: Watchdog 테스트에서는 `autoExtend`를 활성화해야 합니다. 기본 옵션은 자동 확장을 비활성화된 상태로 유지합니다.
