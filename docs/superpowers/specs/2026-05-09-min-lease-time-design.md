# minLeaseTime / lockAtLeastFor 설계

- **Issue**: #38 `lockAtLeastFor 지원 - 최소 락 보유 시간`
- **작성일**: 2026-05-09
- **대상 모듈**: `leader-core`
- **상태**: 설계 (구현 전)

---

## 1. 배경

`leaseTime`은 lockAtMostFor에 해당하며, 작업이 너무 오래 걸릴 때 lock이 자동 만료되는 상한이다. 반대로 작업이 너무 빨리 끝났을 때 최소 시간 동안 lock을 유지하는 `lockAtLeastFor`가 없다.

노드 간 clock skew가 있는 환경에서 작업이 즉시 종료되고 lock이 바로 해제되면, 다른 노드가 같은 schedule tick에서 다시 실행할 수 있다. 이를 줄이기 위해 `minLeaseTime`을 추가한다.

## 2. 범위 분리

#38과 #77이 같은 문제를 다루므로 범위를 나눈다.

- #38: core option + local in-memory 실행 모델에서 최소 보유 시간을 보장한다.
- #77: Redis/Mongo/Hazelcast/ZooKeeper/Exposed backend unlock을 TTL 위임 방식으로 바꾸고 AOP annotation 필드를 복구한다.

이 PR은 #77을 위한 public option과 local reference behavior를 제공한다.

## 3. 공개 API

```kotlin
data class LeaderElectionOptions(
    val waitTime: Duration = DefaultWaitTime,
    val leaseTime: Duration = DefaultLeaseTime,
    val minLeaseTime: Duration = Duration.ZERO,
)

data class LeaderGroupElectionOptions(
    val maxLeaders: Int = DefaultMaxLeaders,
    val waitTime: Duration = DefaultWaitTime,
    val leaseTime: Duration = DefaultLeaseTime,
    val minLeaseTime: Duration = Duration.ZERO,
)
```

검증:

- `minLeaseTime >= Duration.ZERO`
- `minLeaseTime <= leaseTime`

## 4. Local 런타임 의미

### 4.1 Blocking / Virtual Thread

작업 시작 시각을 기록하고, action 완료 또는 예외 후 unlock/release 직전에 남은 최소 보유 시간을 `Thread.sleep`으로 기다린다.

### 4.2 Async `CompletableFuture`

현재 local async 구현은 executor에서 `tryWithLeaderLock { action().join() }` 형태로 lock을 보유한다. 따라서 blocking helper 안의 동일한 `Thread.sleep` 경로가 async에도 적용된다.

### 4.3 Suspend

suspend 구현은 action 완료 또는 예외 후 unlock/release 직전에 남은 최소 보유 시간을 `delay`로 기다린다. cancellation 중에도 release는 실행되어야 하므로 cleanup delay는 `NonCancellable` context 안에서 수행한다.

## 5. 제외 범위

- 분산 backend unlock TTL 위임은 #77에서 처리한다.
- AOP annotation `minLeaseTime` 필드 복구는 #77에서 처리한다.
- `leaseTime` 자동 연장(heartbeat)은 본 범위가 아니다.

## 6. 테스트

- options validation
- local single: 빠른 action 후 즉시 다른 thread/coroutine이 같은 lock을 획득하지 못함
- local group: 빠른 action 후 최소 시간 동안 slot이 유지됨
- exception path: action throw 후에도 minLeaseTime을 지킨 뒤 release
- suspend path: `runSuspendIO`와 `SuspendedJobTester` 패턴 유지

## 7. 리뷰 결정

ShedLock의 backend TTL 위임 방식이 더 이상적이지만, local in-memory 구현은 process-local lock이므로 unlock 직전 대기가 가장 단순하고 검증 가능하다. 분산 backend는 #77에서 storage TTL로 넘겨 호출자 blocking을 제거한다.
