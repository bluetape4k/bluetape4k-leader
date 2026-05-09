# 6-Tier 코드 리뷰 — Issue #77 backend minLeaseTime

## Tier 1. 정확성

- `@LeaderElection` / `@LeaderGroupElection`에 `minLeaseTime = "PT0S"`를 복구했고, AOP가 값을 core option으로 전달한다.
- Core option constructor는 계속 `minLeaseTime >= 0`, `minLeaseTime <= leaseTime`을 검증한다.
- Token/slot backend는 정상 delete/remove/unlock 또는 현재 token을 남은 minimum lease TTL까지 유지하는 release path를 선택한다.
- Redis/Lettuce single lock은 Lua로 `DEL` 또는 `PEXPIRE`를 atomically 선택한다.
- Mongo single/group slot lock은 minimum lease가 남아 있으면 delete하지 않고 `expireAt`을 갱신한다.
- Hazelcast single/group slot lock은 minimum lease가 남아 있으면 같은 token을 짧아진 TTL로 다시 set한다.
- Exposed JDBC/R2DBC single/group slot lock은 minimum lease가 남아 있으면 delete하지 않고 `lockedUntil`을 갱신한다.
- Redisson single lock은 minimum lease가 남아 있으면 `RKeys.expire(Duration, key)`로 Redis lock key TTL을 갱신하고 unlock을 건너뛴다.

## Tier 2. API / 호환성

- `minLeaseTime`은 기본값이 있는 새 annotation property라 기존 annotation 사용은 source compatible이다.
- `DurationParser.parse`의 wait/lease duration 동작은 바꾸지 않았다. Zero duration은 wait/lease에서는 계속 invalid이다.
- `parseNonNegativeOrDefault`는 minimum lease parsing에만 사용한다.
- `leader-core`의 `remainingMinLeaseTime` helper를 public으로 노출해 backend module이 time arithmetic을 중복 구현하지 않게 했다.

## Tier 3. 동시성 / 취소

- Suspend release path는 기존 `NonCancellable` cleanup wrapper를 유지한다.
- Backend TTL delegation은 fast completion 이후 caller thread/coroutine을 block하지 않는다.
- Redisson `RLock`은 같은 thread에 대해서 reentrant한 성질이 남는다. Cross-thread/node contender는 Redis TTL test로 검증했다.
- Hazelcast test는 sub-second assertion이 안정적이지 않아 1초 이상의 TTL을 사용한다.

## Tier 4. Backend coverage

- 새 fast-return test로 검증한 범위: Lettuce single, Redisson single, Mongo single, Hazelcast single, Hazelcast group.
- Release-path 변경 후 기존 regression suite로 검증한 범위: Exposed JDBC locks/groups, Exposed R2DBC locks/groups/electors.
- Local minLeaseTime behavior는 #38에서 구현했고 이 PR 아래에 stacked되어 있다.
- 알려진 gap: Lettuce/Redisson group implementation은 per-slot token 기반이 아니라 counter/semaphore 기반이다. 획득한 Redis group permit 하나에 정확한 `minLeaseTime` TTL을 위임하려면 slot-token redesign이 필요하다.

## Tier 5. Test / 커버리지

- Focused annotation test가 `minLeaseTime` default와 custom value를 검증한다.
- AOP test가 factory option을 capture해 single/group `minLeaseTime` mapping을 검증한다.
- Parser/cache test가 zero minimum lease와 cache-key 분리를 검증한다.
- Backend test가 immediate reacquisition은 minimum lease 전에는 실패하고 expiry 이후에는 성공함을 검증한다.
- `leader-core` coverage는 stacked #38 branch 기준 80% target을 넘는다.

## Tier 6. 문서 / 운영

- Root/module README는 `minLeaseTime`을 lockAtLeastFor-style lease retention으로 설명한다.
- Mongo/Hazelcast backend README는 storage TTL retention behavior를 문서화한다.
- Spring Boot README example은 annotation `minLeaseTime` 사용법을 보여준다.
- PR은 #38/#149 검토와 merge 전까지 `feat/38-min-lease-time` base의 draft/stacked PR로 유지한다.

## 잔여 위험

- Redis group의 정확한 per-permit TTL은 현재 counter/semaphore model에 acquired permit별 token이 없어서 이 patch에서 해결하지 않았다.
- Redisson single-lock TTL delegation은 Redisson의 현재 key naming과 `RKeys.expire` behavior에 의존한다. RedisServer 기반 integration test로 검증했다.
- Exposed minLeaseTime은 기존 regression coverage로 release path를 검증했지만, 이 patch에는 별도의 explicit minLeaseTime test가 없다.
