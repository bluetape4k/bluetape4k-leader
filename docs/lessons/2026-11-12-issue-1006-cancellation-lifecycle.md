# Issue #1006 교훈 — 비동기 취소 전파의 관찰 경계 (2026-11-12)

## 맥락

Full Nightly `43110046685`에서 DynamoDB 취소 회귀는 Attempt 1, Redisson
취소 회귀는 Attempt 1·2에서 `actionFuture.isCancelled` 즉시 단정에 실패했고,
retry에서만 통과했다. 두 backend는 `LeaderFutureBridge.CancellationRelay`를
공유한다.

## 원인

`actionStarted`는 action callback 내부에서 future를 반환하기 전에 먼저 호출된다.
따라서 latch가 열리고 caller가 `result.cancel(false)`를 호출하는 시점에는
relay의 `actionFuture.set(...)`이 아직 실행되지 않을 수 있다. relay는 future를
등록한 뒤 이미 취소된 state를 재확인하므로 eventual cancellation은 보장되지만,
action 시작 신호나 caller `cancel` 반환은 action cleanup 완료 신호가 아니다.

## 결정

- `LeaderFutureBridge`와 public API는 변경하지 않는다.
- Redisson의 nullable/result single/group 네 경로와 DynamoDB group nullable
  경로에서 action 취소를 `await atMost ... untilAsserted`로 관찰한다.
- action future 취소와 lock/permit 재획득을 별도 assertion으로 유지한다.
- fixed sleep이나 retry 횟수 증가는 사용하지 않는다.

## 검증

- Redisson `RedissonExecutorRejectionLifecycleTest`: 6/6 통과.
- DynamoDB 대상 integration test: 1/1 통과.
- `LeaderFutureBridgeTest`: 12/12 통과.
- DynamoDB·Redisson `detekt`: 통과.
- `git diff --check`: 통과.

## 재발 방지

비동기 테스트에서 callback 진입 latch를 future publication 또는 cleanup 완료와
동일시하지 않는다. 취소 전파는 bounded Awaitility로 관찰하고, resource 재획득
검증을 별도 lifecycle assertion으로 둔다. retry-green 결과만으로 첫 시도의
race를 지우지 않으며, 같은 공통 bridge를 사용하는 backend의 cancellation
테스트를 함께 점검한다.
