# Issue #886 WebSocket subscriber cleanup 대기

## 맥락

[`LeaderEventStreamRouteTest`](../../leader-ktor/src/test/kotlin/io/bluetape4k/leader/ktor/LeaderEventStreamRouteTest.kt)의 WebSocket 종료 검증은 client가 `Close` frame을 보낸 뒤 `subscriberCount()`가 `0`이 될 때까지 반복해서 조회했습니다. 이 검증은 전체 빌드 부하에서 5초 timeout으로 간헐적으로 실패했지만, 해당 테스트만 다시 실행하면 통과했습니다.

## 결정

`LeaderEventStreamHub.awaitSubscriberCount(expected)`를 최소 개수가 아니라 정확한 subscriber 수에 도달할 때 완료되는 내부 test barrier로 정의했습니다. WebSocket 검증은 client의 `closeReason`으로 close handshake 완료를 확인한 뒤 이 barrier로 `releaseConnection()` 완료를 기다립니다. WebSocket 종료 뒤 cleanup을 유도하려고 추가 event를 발행하지 않습니다.

timeout 값과 운영 WebSocket adapter는 변경하지 않았습니다. 수정 범위는 내부 test helper와 테스트에 한정됩니다.

## 결과

cleanup 검증은 `subscriberCount()` polling 중 `releaseConnection()`과 같은 mutex를 반복해서 경합하지 않습니다. WebSocket 테스트는 정상 close handshake 뒤 subscriber와 connection permit이 반환되는 계약을 검증합니다. `awaitSubscriberCount(0)`가 connection release 전에 조기 완료되지 않는 회귀 테스트도 추가했습니다.

## 검증

- 회귀 테스트 RED: 기존 구현에서 `cleanup.isCompleted`가 `true`여서 실패
- 회귀 테스트 GREEN: 1/1 통과
- WebSocket cleanup 테스트: 독립 실행 5/5 통과
- `:bluetape4k-leader-ktor:test`: 124/124 통과
- `detekt`: 38 tasks 통과
- 전체 `build`: 4,363 tests, failures 0, errors 0, skipped 0
- `git diff --check`

## 놓친 점

기존 `awaitSubscriberCount()`의 이름은 정확한 상태 대기를 암시했지만 구현은 `>= expected` 조건이었습니다. 그래서 `expected = 0`은 활성 subscriber가 있어도 즉시 완료됐고, 테스트는 별도의 busy polling과 post-close event에 의존하게 됐습니다. 또한 client의 `close()` 호출은 close handshake 완료를 증명하지 않았습니다. 단독 재실행 성공만 확인하면 이 두 synchronization 공백을 놓칠 수 있습니다.

## 향후 가드

WebSocket cleanup 테스트는 상태를 반복 조회하거나 종료 뒤 추가 traffic을 발생시켜 cleanup을 유도하지 않습니다. 먼저 `closeReason`으로 close handshake 완료를 확인하고, resource를 반환하는 코드와 같은 synchronization 경계에서 완료 신호를 기다립니다. 반환 전에는 barrier가 완료되지 않는다는 회귀 테스트도 함께 둡니다. SSE처럼 close handshake가 없는 transport는 heartbeat나 event write로 disconnect를 관측해야 한다는 차이를 유지합니다.
