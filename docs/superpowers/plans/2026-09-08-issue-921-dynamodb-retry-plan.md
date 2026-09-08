# #921 DynamoDB 조회 재시도 상한

## 범위와 승인

#925의 승인된 순차 PR 계획 중 두 번째 작업이다. 기준은 `ded0504b`, base `develop`,
head `fix/issue-921-dynamodb-retry`다. 머지와 새 의존성은 포함하지 않는다.

## 원인과 계약

`DynamoDbLockClient.batchRead`는 성공 응답의 미처리 키를 제한 없이 재요청한다.
SDK 개별 요청 timeout은 이 반복을 제한하지 않는다. sync/async client가 같은 반복문을 사용한다.
전체 조회에서 추가 재시도 8회를 공유하고, 25ms부터 200ms까지 제한된 지수 backoff를 적용한다.
한도 초과는 `IllegalStateException`으로 알리며 수집된 일부 결과를 정상 state로 반환하지 않는다.
이는 총 wall-clock timeout이 아니다. 개별 SDK 호출 timeout은 caller client 설정을 따른다.

기존 AWS BatchWrite helper는 BatchGet 대체물이 아니며 Enhanced/Spring API도 추가하지 않는다.
시간 deadline 대신 유한 retry 상한을 선택하므로 `MonotonicDeadline`을 불필요하게 끼워 넣지 않는다.
대기 함수만 내부 생성자에 주입하여 실제 시간을 소비하지 않고 backoff를 검증한다.

## 순서와 검증

1. 기존 소스·state 호출자·GNO 및 live #921을 확인한다.
2. sync/async fake client가 동일 키를 계속 반환하는 테스트로 RED를 확인한다. 테스트 자체의 무한 실행은 sentinel로 방지한다.
3. 전역 retry 횟수와 bounded backoff를 추가한다. sleep 중 interrupt 및 SDK 오류는 정상 상태로 숨기지 않는다.
4. 일부 성공, 다중 chunk, 만료/missing slot, consistentRead와 global retry budget을 검증한다.
5. 모듈 테스트·detekt·ABI를 순차 실행하고 README 두 언어와 lesson을 갱신한다.
6. 최종 작성자 코드/설계 검토, commit/push, PR 생성 및 exact-head CI 확인. 머지는 보류한다.

실패하면 해당 회귀로 돌아가 수정한다. 되돌릴 때는 이 branch의 좁은 diff만 대상으로 삼고 다른 worktree는 보존한다.
