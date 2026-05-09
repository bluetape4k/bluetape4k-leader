# Backend minLeaseTime TTL 위임 설계

## 목표

Issue #77은 #38에서 core `minLeaseTime` option과 local reference behavior를 추가한 뒤 남은 ShedLock-style `lockAtLeastFor` 경로를 완성한다.

핵심 backend 규칙은 다음과 같다. action이 `minLeaseTime`보다 빨리 끝나면 caller는 즉시 반환되어야 하고, storage backend가 남은 최소 lease 시간 동안 lock을 계속 사용할 수 없게 유지해야 한다. Backend adapter는 `minLeaseTime`을 만족시키기 위해 application thread를 block하지 않는다.

## 범위

- `@LeaderElection`과 `@LeaderGroupElection`에 `minLeaseTime`을 복구한다.
- Annotation duration을 `LeaderElectionOptions` / `LeaderGroupElectionOptions`로 전달한다.
- Core option constructor의 fail-fast validation을 유지한다. 규칙은 `minLeaseTime >= 0`, `minLeaseTime <= leaseTime`이다.
- Backend release path에서 현재 보유 token/slot을 남은 최소 lease 시간까지 유지한다.
- 빠른 action 반환 시나리오를 검증하는 focused integration/unit test를 추가한다.

## Backend 전략

### Token/slot backend

MongoDB, Hazelcast, Exposed JDBC, Exposed R2DBC, Lettuce single lock은 기존 lock row/key를 삭제하지 않고 TTL 또는 expiry column을 갱신할 수 있다.

- `remaining = acquiredAt + minLeaseTime - now`를 계산한다.
- `remaining > 0`이면 현재 token을 유지하고 backend expiry를 `now + remaining`으로 갱신한다.
- 그렇지 않으면 기존 unlock/delete를 수행한다.

이 방식은 caller fast return과 node-death behavior를 함께 보존한다.

### Redisson single lock

`RLock`은 `expire` API를 직접 노출하지 않지만, Redisson `RKeys.expire(Duration, key)`로 Redis lock key의 TTL을 갱신할 수 있다. 현재 lock owner가 유효하고 `remaining > 0`이면 key TTL을 갱신하고 `unlock`을 건너뛴다. 그렇지 않으면 기존 Redisson unlock path를 사용한다.

같은 thread reentrancy는 Redisson `RLock`의 성질로 남는다. 다른 thread/node contender는 key가 만료될 때까지 block된다.

### Redis semaphore-style group

현재 Lettuce/Redisson group implementation은 per-slot token row가 아니라 counter/semaphore 기반이다. 획득한 permit 하나에 서로 다른 TTL을 안전하게 붙이려면 backend model을 바꿔야 한다.

따라서 이 PR은 slot token이 이미 있는 backend와 single lock 경로에 `minLeaseTime`을 구현한다. Redis group의 정확한 TTL 위임은 slot-token redesign이 필요할 때 후속 작업으로 다룬다.

## AOP 계약

`minLeaseTime`은 `waitTime`, `leaseTime`과 같은 duration parser 계열을 사용하되 zero duration을 허용한다.

- 빈 문자열은 zero duration을 의미한다.
- ISO-8601(`PT10S`)과 simple value(`10s`, `500ms`)를 허용한다.
- 잘못된 값이거나 `leaseTime`보다 큰 값은 metadata resolution 시점에 실패한다.

Aspect는 sleep하지 않는다. Aspect는 선택된 factory option으로 `minLeaseTime`만 전달한다.

## 위험

- Expiry는 backend clock에 의존한다. Clock skew는 distributed locking의 기존 위험이며 `leaseTime`과 함께 문서화해야 한다.
- Redisson key TTL 조작은 lock key name과 Redisson Redis data layout에 의존한다. 실제 RedisServer 기반 test로 검증한다.
- Redis group semaphore의 정확한 semantics는 작은 release-path patch가 아니라 slot-token redesign이 필요하다.
