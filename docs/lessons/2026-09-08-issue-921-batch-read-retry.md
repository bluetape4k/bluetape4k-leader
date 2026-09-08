# 성공 응답의 미처리 항목에도 재시도 상한을 둔다

## 원인과 결정

#921의 `DynamoDbLockClient.batchRead`는 HTTP/SDK 요청이 성공해도 미처리 키가 계속 반환될 수 있었다.
SDK timeout으로 반복문 전체가 제한된다는 가정은 틀렸다. fake client가 같은 키를 10번째까지
반환하자 sync/async 두 재현 테스트 모두 의도한 한도 초과 예외 대신 sentinel `AssertionError`로 실패했다.

전체 조회의 추가 재시도 8회를 chunk 사이에서 공유하고 25~200ms의 제한된 지수 backoff를 적용했다.
미처리 키가 남으면 `IllegalStateException`을 던진다. 이미 수집한 일부 결과를 정상 state로 반환하면
빈 슬롯 수를 과대평가하므로 부분 성공을 숨기지 않는다. 정상 경합의 `null` 계약은 바꾸지 않는다.

BatchWrite 전용 helper와 Enhanced/Spring helper는 low-level BatchGet 계약에 맞지 않아 추가하지 않았다.
retry 횟수 상한만으로 이번 반복을 제한할 수 있으므로 별도의 deadline 추상화도 추가하지 않았다.
대기 함수는 내부 생성자에서만 주입한다. 개별 SDK 요청이 끝난다는 가정은 caller client의 timeout 책임으로 남는다.

## 검증

- RED: sync/async의 연속 미처리 키 테스트 2건 실패.
- GREEN: 상한·chunk 공유·일부 성공·expired/missing slot·consistentRead·interrupt 테스트 7건 포함, 모듈 전체 128개 통과.
- 모듈 detekt 성공. ABI는 16개 산출물에서 미분류 변경 0건이며, 내부 생성자 변경과 기존 Lettuce synthetic accessor 2건은 분류된 제외다.
- README 두 언어에 retry 상한과 총 timeout의 차이를 명시했다. PR head CI는 별도로 확인한다.

## 재발 방지

미처리 항목을 다시 요청하는 반복문은 서비스 성공 응답과 무관하게 종료 조건을 검증한다.
상한을 chunk마다 초기화하지 말고 전체 작업의 retry 예산을 테스트한다. 실제 sleep 대신 주입한 대기를
기록하여 횟수와 최대 지연을 검증한다. 전체 timeout을 보장하지 않는 코드에는 그런 보장을 문서화하지 않는다.
