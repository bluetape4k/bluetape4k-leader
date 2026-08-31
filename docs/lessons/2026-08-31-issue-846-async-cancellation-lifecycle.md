# 반환 future의 취소는 내부 락 생명주기까지 연결해야 한다

## 맥락

Issue [#846](https://github.com/bluetape4k/bluetape4k-leader/issues/846)는 Exposed JDBC single async 경로가 dependent future를 그대로 반환해 호출자 취소를 획득 작업과 action future에 전달하지 못하는 문제를 다뤘다.

## 놓친 가정과 근거

`thenComposeAsync`로 만든 결과 future를 취소하면 upstream 작업도 함께 취소된다는 가정이 잘못됐다. 획득 대기, compose callback 직전, history 기록 직후, action 실행 중에 결과를 취소하는 회귀 테스트에서 action 또는 unlock이 남거나 취소가 내부 future에 전달되지 않았다.

## 결정

- 호출자에게 반환하는 `resultFuture`와 내부 `actionFuture`를 명시적으로 연결한다.
- 획득 재시도와 action 경계마다 취소 상태를 확인한다.
- terminal guard 하나가 watchdog, history, unlock, 결과 완료를 정확히 한 번만 처리한다.
- 취소 history는 group async 경로와 같은 `FAILED` 계약을 사용한다.

## 결과와 검증

커밋 `7677a4f9`에서 취소 경계와 락 재획득을 검증했다. Exposed JDBC 전체 334개 테스트가 H2, PostgreSQL, MySQL에서 통과했고 `detekt`와 binary compatibility 검사도 통과했다.

## 재발 방지

새 async backend를 검토할 때 반환 future만 확인하지 않는다. acquisition, acquired-before-action, action, terminal cleanup 상태를 나누고 각 상태에서 caller cancellation을 주입해 내부 작업 취소와 exactly-once cleanup을 함께 검증한다. 이미 실행 중인 JDBC transaction의 강제 중단은 별도 driver 계약으로 취급한다.
