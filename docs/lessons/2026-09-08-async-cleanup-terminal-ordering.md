# Async cleanup 실패는 cleanup 완료 뒤에 노출한다

## 배경

Consul, etcd, Kubernetes async elector는 lock을 획득한 뒤 action을 제출하기 전에 executor가 요청을 거부할 수 있다. 기존 구현은 이때 lease cleanup을 별도 virtual thread에 맡기고 원래 실패를 즉시 반환했다. caller가 같은 lock이나 slot을 바로 다시 획득하면 이전 cleanup과 경쟁했다.

## 원인

`AsyncLeaseCleanupDispatcher.execute`가 fire-and-forget 방식이어서 pipeline failure와 cleanup completion 사이에 순서가 없었다. 또한 primary executor와 fallback executor가 모두 실패하면 caller completion thread에서 cleanup을 실행했고, `RejectedExecutionException` 이외의 scheduler 예외는 결과 future를 완료하지 못했다.

## 결정

- caller completion thread에서는 blocking lease cleanup을 실행하지 않는다.
- scheduler가 어떤 `Throwable`을 던져도 결과 future를 terminal 상태로 만든다.
- action 시작 전 실패는 획득 완료와 cleanup 요청을 barrier로 합류시킨 뒤 원래 실패를 반환한다.
- 중복 cleanup 요청은 하나의 completion future를 공유하고 backend release를 정확히 한 번만 제출한다.
- action이 시작된 뒤에는 기존 action cleanup 경로가 lease를 소유한다. pre-action barrier와 action cleanup이 같은 lease를 함께 정리하지 않는다.

## 결과와 검증

세 backend의 single/group 경로에서 second executor rejection을 주입하고 cleanup을 의도적으로 차단했다. cleanup 중에는 결과 future가 완료되지 않았고, 차단을 해제한 뒤 원래 rejection이 보존됐으며 같은 lock과 slot을 즉시 다시 획득했다. dispatcher에는 비표준 scheduler 예외, 이중 handoff 실패, failure precedence, barrier exactly-once 테스트를 추가했다.

검증은 대상 세 모듈 전체 테스트, `detekt`, `checkBinaryCompatibility`로 수행했다. binary compatibility 검사는 16개 artifact에서 `unknown=0`과 공개 비호환 0을 확인했다.

## 놓친 점

정상 action completion의 thread 격리만 검증하면 pre-action failure ordering은 드러나지 않는다. cleanup 호출 횟수만 확인해도 caller가 cleanup 전에 실패를 관찰하는 race를 찾을 수 없다.

## 향후 지침

async resource 획득 뒤 action 제출이 실패할 수 있는 경로에는 다음 두 조건을 함께 검증한다.

1. cleanup이 막힌 동안 caller-visible failure가 완료되지 않는다.
2. cleanup 완료 직후 같은 resource를 재획득할 수 있고 release side effect는 한 번만 발생한다.

`CompletableFuture.cancel()`은 즉시 terminal 상태가 되는 표준 계약을 유지한다. cancellation 테스트는 취소가 action 또는 pre-action cleanup 소유자에게 전달되고, 늦게 획득한 lease도 정확히 한 번 정리되는지를 별도 lifecycle 증거로 확인한다.
