# DynamoDB 슬롯 상태 7계층 검토

날짜: 2026-07-04 범위: Issue #573, 마일스톤 0.5.0

## 검토된 모듈

- `leader-dynamodb`: 그룹 상태 및 활성 개수 조회 경로.

## 7계층 결과

1. 정확성: 통과
   - 이제 그룹 상태는 `0 until maxLeaders`에 대한 결정적 슬롯 키를 읽습니다.
   - 누락 및 만료된 슬롯 행은 반환된 `LeaderLease` 목록에서 제외됩니다.

2. API 및 계약 호환성: 통과
   - 공개 선거인 API 및 옵션 모델은 변경되지 않았습니다.
   - `state()` 및 `activeCount()`는 동일한 빈, 부분, 전체 및 해제된 슬롯 의미 체계를 유지합니다.

3. 동시성 및 취소: PASS
   - 획득, 릴리스, 감시 또는 코루틴 취소 경로는 변경되지 않았습니다.
   - 비동기 지원 일시 중지 상태 읽기는 이제 제한된 일괄 가져오기 호출을 통해 여전히 동일한 클라이언트 경계를 사용합니다.

4. 백엔드 소유권 안전성: 통과
   - 조회는 검증된 논리적 잠금 이름에서 파생된 결정적 그룹 슬롯 키로 제한됩니다.
   - 그룹 상태 핫 경로에는 테이블 전체 접두사 스캔이 남아 있지 않습니다.

5. 테스트: 합격
   - 결정론적 슬롯 키, 만료된 슬롯 필터링, 누락된 슬롯, 100개 키의 일괄 청크, 처리되지 않은 키 재시도 및 제로 스캔 호출에 대한 내부 클라이언트 테스트가 추가되었습니다.
   - 기존 DynamoDB 그룹 통합 테스트는 비어 있음, 점유됨, 가득 참, 해제 및 재획득 동작을 다룹니다.

6. 보안 및 관찰 가능성: 통과
   - 자격 증명이나 토큰 로깅이 변경되지 않습니다.
   - 더 이상 관련되지 않은 테이블 행을 통해 조회할 수 없습니다.

7. 유지보수성: 합격
   - 일괄 조회 동작은 `DynamoDbLockClient`에 집중되어 있습니다.
   - 선출기는 그룹 접두사와 `maxLeaders`만 전달하며 DynamoDB 내부 패키지에 키 파생을 유지합니다.

## 검증 증거

- `./gradlew :bluetape4k-leader-dynamodb:compileKotlin :bluetape4k-leader-dynamodb:compileTestKotlin --warning-mode all`
- `./gradlew :bluetape4k-leader-dynamodb:test --tests 'io.bluetape4k.leader.dynamodb.internal.DynamoDbLockClientStateLookupTest' --tests 'io.bluetape4k.leader.dynamodb.DynamoDbLeaderGroupElectorIntegrationTest' --tests 'io.bluetape4k.leader.dynamodb.DynamoDbSuspendLeaderGroupElectorIntegrationTest' --warning-mode all`
- `./gradlew :bluetape4k-leader-dynamodb:test --warning-mode all`
- `rg -n "ScanRequest|\\.scan\\(" leader-dynamodb/src/main leader-dynamodb/src/test -g '*.kt'`
- `git diff --check`

## 재시도 참고

첫 번째 전체 DynamoDB 모듈 실행에서는 타이밍에 민감한 기존 감시 통합 오류가 하나 노출되었습니다. 실패한 테스트는 직접 다시 실행했을 때 통과되었으며 전체 모듈은 27개의 통과 테스트를 통해 다음 실행에서 통과되었습니다.

## Deferred 검증

전체 저장소 테스트는 요청된 워크플로우에 따라 전체 스택 이슈 트레인이 구현될 때까지 의도적으로 연기됩니다.
