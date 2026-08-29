# Issue #795: shutdown timeout을 종료 상태로 보존

## Context

`MongoLeaderHistoryIndexer.closeSuspend()`는 scope를 취소한 뒤 제한 시간 안에 index build job의
종료를 기다린다. 기존 구현은 timeout 경고만 기록하고 `indexState=0`을 유지했다. 운영자는 실행 중인
빌드와 이미 timeout으로 끝난 shutdown 대기를 메트릭에서 구분할 수 없었다.

## Decision

인덱스 빌드의 수명 주기를 `BUILDING`, `READY`, `FAILED`, `SHUTDOWN_TIMEOUT`으로 표현한다. 기존
`indexState: Int`와 게이지는 호환성을 위해 `0`, `1`, `-1`, `-2` 코드를 유지하고, 타입 상태는
`indexLifecycleState`로 제공한다.

build job은 `BUILDING`에서만 `READY`나 `FAILED`로 전이한다. `closeSuspend()`가 자체 timeout을 관찰하면
`SHUTDOWN_TIMEOUT`을 기록한다. 이후 `NonCancellable` 구간의 작업이 늦게 끝나도 이 종료 상태를
덮어쓰지 못한다. 반면 외부 호출자 취소는 기존대로 전파하며 timeout 상태를 기록하지 않는다.

## Outcome

shutdown timeout은 로그뿐 아니라 타입 상태, 기존 정수 상태, Micrometer 게이지에서 동일하게 관찰된다.
중복 close는 종료 상태를 유지하고, 새 indexer 인스턴스는 독립적으로 `BUILDING`에서 시작한다.

## Verification

- RED: timeout 후 `indexState`가 `-2`여야 한다는 회귀 테스트에서 기존 값 `0`을 확인했다.
- GREEN: timeout 상태, 게이지 `-2.0`, 중복 close, 지연 완료, 호출자 취소, 재시작 동작을 targeted
  테스트로 검증했다.
- 전체 모듈 테스트는 142건 모두 통과했고 failures, errors, skipped는 각각 0건이었다.
- Detekt, ABI 호환성, `git diff --check`, 독립 코드 리뷰를 모두 통과했다.

## Miss or Surprise

timeout 직후 상태만 바꾸면 충분하지 않다. 취소에 즉시 응답하지 않는 작업이 timeout 이후 성공하거나
실패하면서 종료 상태를 다시 덮어쓸 수 있다. timeout 기록과 build 완료 전이는 같은 atomic state를
공유해야 한다.

## Future Guard

제한 시간이 있는 shutdown을 제공하는 background 작업은 다음 조건을 함께 고정한다.

1. 자체 timeout과 외부 cancellation을 서로 다른 결과로 처리한다.
2. timeout으로 관찰된 종료 상태는 늦은 작업 완료가 덮어쓰지 못한다.
3. 중복 close와 새 인스턴스 시작이 기존 인스턴스의 종료 상태를 재활성화하지 않는다.
4. 로그, 타입 상태, 숫자 메트릭이 같은 수명 주기 의미를 사용한다.
