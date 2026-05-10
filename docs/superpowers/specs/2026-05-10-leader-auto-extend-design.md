# Leader autoExtend 설계

## 문제

Issue #73은 `@LeaderElection(leaseTime = "PT5M")` 본문이 lease를 초과 실행할 때 기존 backend TTL이 만료되고 다른 노드가 같은 lock을 획득하는 split-brain 위험을 줄이기 위해 `autoExtend` 옵션을 요구한다.

현재 코드 근거:

- `LeaderElectionOptions`는 `waitTime`, `leaseTime`, `nodeId`, `minLeaseTime`만 가진다.
- `@LeaderElection`은 `waitTime`, `leaseTime`, `minLeaseTime`, `bean`, `failureMode`만 노출한다.
- `LeaderElectionAspect`는 annotation duration을 `LeaderElectionOptions`로 변환해 factory에 넘긴다.
- Lettuce/Mongo lock은 token을 저장하고 unlock 시 token 조건을 확인하므로 owner-conditional TTL 연장이 가능하다.
- Redisson `RLock.tryLock(waitTime, TimeUnit)`은 Redisson 내부 watchdog을 사용한다. Redisson 공식 문서는 watchdog이 holder Redisson instance가 살아 있는 동안 lock expiration을 연장하며 기본 timeout이 30초라고 설명한다.
- MongoDB TTL index 공식 문서는 date field 기반 TTL index가 문서를 자동 제거한다고 설명한다. 현재 Mongo backend는 `expireAt` TTL index를 사용한다.
- Redis `PEXPIRE` 공식 문서는 key expiration을 millisecond 단위로 설정한다고 설명한다. Lettuce backend는 Lua unlock script가 이미 token 조건부 `PEXPIRE`를 사용한다.

Primary references:

- GitHub issue: https://github.com/bluetape4k/bluetape4k-leader/issues/73
- Redisson locks: https://redisson.pro/docs/data-and-services/locks-and-synchronizers/
- Redis PEXPIRE: https://redis.io/docs/latest/commands/pexpire/
- MongoDB TTL indexes: https://www.mongodb.com/docs/v8.0/core/index-ttl/

## 목표

- `LeaderElectionOptions.autoExtend: Boolean = false`를 추가한다.
- `@LeaderElection(autoExtend = true)`를 추가하고 `LeaderElectionAspect`가 core option으로 전달한다.
- Single-leader path에서 lock/elector가 auto-extend lifecycle을 소유한다.
- v1 production slice는 Redisson, Lettuce, MongoDB, Local single-leader backend를 지원한다.
- `@LeaderGroupElection` autoExtend는 이번 PR 범위 밖이다.

## 비목표

- Group/semaphore backend autoExtend 구현.
- Fencing token 도입.
- AOP-only timer 구현.
- 모든 backend(Exposed/Hazelcast/ZooKeeper)의 완전한 autoExtend 구현. 단, common option 추가로 compile 영향이 있으면 수정한다.

## 설계

### Core option

`LeaderElectionOptions`에 `autoExtend`를 추가한다. 기본값은 false이므로 기존 호출자는 fixed TTL 동작을 유지한다.

Validation은 기존 `minLeaseTime <= leaseTime` 규칙을 유지한다. `autoExtend + minLeaseTime`은 backend별 release semantics가 다르므로 core에서 금지하지 않고, Redisson single lock에서만 fail-fast 한다.

### AOP

`@LeaderElection`에 `autoExtend: Boolean = false`를 추가한다. `LeaderElectionAspect.resolveMetadata()`는 annotation 값을 `LeaderElectionOptions(autoExtend = ann.autoExtend)`로 전달한다.

Aspect는 timer를 만들지 않는다. sync/suspend/Mono 모두 factory cache key에 포함된 option으로 backend elector를 선택한다.

### Common watchdog helper

Core에 `LeaderLeaseAutoExtender`를 둔다. Backend는 lock 획득 직후 `start(autoExtend, leaseTime) { extend(leaseTime) }`를 호출하고, action 종료/예외/취소 release path에서 close한다.

Watchdog은 leaseTime의 약 1/3 주기로 실행하며, `extend()`가 false를 반환하거나 예외를 던지면 반복을 중단하고 warn log를 남긴다. 이미 소유권을 잃은 lock을 되살리지 않기 위해 extension 함수는 반드시 owner-token 조건부여야 한다.

### Backend

- Redisson: `autoExtend=true`이면 명시 leaseTime 대신 `tryLock(waitTime, TimeUnit)` / `tryLockAsync(waitTime, TimeUnit, lockId)`를 사용해 Redisson watchdog에 위임한다. `minLeaseTime > 0`과 조합하면 현재 key TTL 조작 release semantics가 watchdog과 충돌할 수 있어 fail-fast 한다.
- Lettuce: `LettuceLock.extend(leaseTime): Boolean`과 suspend variant를 추가한다. Lua script는 `GET key == token`일 때만 `PEXPIRE key leaseMs`를 수행한다.
- MongoDB: `MongoLock.extend(leaseTime): Boolean`과 suspend variant를 추가한다. `updateOne(_id, token, $set expireAt=now+leaseTime)`의 matched count로 성공 여부를 판단한다.
- Local: JVM-local lock은 action 실행 동안 `ReentrantLock`/`Mutex`가 이미 유지되므로 split-brain 위험이 없다. `autoExtend`는 state registry `leaseUntil` 관측값 갱신만 수행한다.

## 접근 비교

1. AOP timer only
   - Rejected: issue #73의 split-brain 원인은 backend TTL이고, AOP는 lock token을 알 수 없다. owner-conditional 연장을 보장할 수 없다.
2. Common lock interface에 `extend()` 추가
   - Rejected for v1: 모든 backend lock abstraction을 한 번에 바꾸면 Exposed/Hazelcast/group까지 scope가 넓어진다.
3. Backend-local extension plus small common scheduler
   - Accepted: lifecycle ownership을 elector/lock에 두면서 issue가 요구한 4 backend slice를 작게 구현한다.

## 위험과 완화

- Watchdog thread가 action 종료 후 살아남는 위험: `AutoCloseable` close를 finally/whenComplete/NonCancellable에 둔다.
- Token이 만료된 뒤 다른 node가 획득한 lock을 연장하는 위험: Lettuce/Mongo extension은 token 조건부 Lua/update만 사용한다.
- Redisson `minLeaseTime + autoExtend` release 모호성: Redisson elector constructor에서 fail-fast하고 테스트한다.
- 짧은 leaseTime에서 scheduler jitter로 갱신이 늦는 위험: 테스트 lease는 200ms 이상으로 두고, production에서는 leaseTime을 backend RTT보다 충분히 크게 문서화한다.
- Watchdog 연장 실패 후 action은 이미 실행 중이다. 현재 API는 실행 중 action 취소를 강제할 수 없으므로 warn 후 반복 중단으로 제한한다.

## Acceptance Criteria

- `LeaderElectionOptions.Default.autoExtend == false`.
- `@LeaderElection(autoExtend = true)` 값을 aspect가 factory option으로 전달한다.
- Local/Lettuce/Mongo long-running test에서 `leaseTime`을 초과해도 contender가 lock을 획득하지 못한다.
- Redisson autoExtend는 watchdog acquisition path를 사용하고 `minLeaseTime > 0` 조합은 fail-fast 한다.
- `@LeaderGroupElection`에는 autoExtend API를 추가하지 않는다.
