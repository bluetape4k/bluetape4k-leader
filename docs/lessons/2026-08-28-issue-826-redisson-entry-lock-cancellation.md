# #826 Redisson entry lock 취소 waiter가 남은 문제

## 문제

Redisson suspend 후보 registry가 entry lock을 `lockAsync(threadId)`로 기다리면,
호출 coroutine이 취소되어도 Redis의 pub/sub waiter가 owner 해제 또는 연결 복구까지
남을 수 있다. 취소된 caller만 빠르게 반환하고 원본 lock future를 계속 보존하는 방식은
늦은 획득을 정리할 수 있지만, owner가 오래 유지되는 동안 waiter lifecycle을 끝내지
못한다.

## 수정

- `tryLockAsync(wait, lease, unit, threadId)`를 500ms bounded attempt로 사용하고,
  실패하면 취소 여부를 확인하며 다음 attempt를 시작한다.
- 성공한 attempt는 `leaseTime=-1`을 사용해 Redisson watchdog 갱신을 유지한다. source
  future가 2초 deadline을 넘기면 원본 future를 취소해 backend 지연이 coroutine waiter를
  붙잡지 않게 한다.
- suspend entry lock의 logical owner ID는 음수 namespace에서 할당해 같은 RedissonClient의
  blocking JVM thread ID(양수)와 reentrant owner로 오인되지 않게 한다.
- 취소와 실제 획득이 경합하면 기존 상태 기계가 late acquisition을 관찰해 bounded
  `unlockAsync` cleanup을 예약한다.
- Toxiproxy 회귀 시험은 upstream 500ms 지연으로 취소된 acquire 명령을 Redis에 남겨 두고,
  owner를 해제한 뒤 늦은 owner가 실제로 생성되는지와 cleanup으로 사라지는지를 각각
  확인한다. 이후 새 logical thread가 bounded 시간에 재획득하는지도 검증한다.

## 예방 규칙

실패한 가정: coroutine 취소가 caller를 반환시키면 backend lock waiter도 곧 끝난다.

발견 증거 또는 교정: Redisson `lockAsync`는 owner가 유지되는 동안 pub/sub waiter를
계속 보유하며, `CompletableFutureWrapper.cancel()`은 노출된 future만 취소하므로 내부
Redis 명령과 서버의 실제 lock 획득 시점은 어긋날 수 있다. JVM thread ID와 coroutine
logical ID를 섞으면 같은 owner로 취급되어 대기를 우회할 수도 있다.

수정 결정: 무기한 `lockAsync` 대신 watchdog을 유지하는 bounded `tryLockAsync` 시도와
source future 취소, late-acquisition cleanup을 함께 사용한다. P1 review 지적은 기존
green CI보다 우선하는 blocker로 취급하고, non-completing source와 owner-held 실제
late-acquisition integration 경계를 추가한다. 내부 명령의 server-side 취소를 가정하지
않고 늦은 획득 관찰과 owner-specific cleanup을 증명한다.

향후 예방 확인: suspend distributed lock 대기 경로를 추가하거나 바꿀 때는 bounded
wait, watchdog lease, source cancellation deadline, blocking/suspend logical owner namespace,
cancellation race, owner-held cleanup, unlock 응답 timeout을 mock과 실제 Redis/Toxiproxy에서
각각 검증한다. future 취소를 server-side 명령 취소로 표현하지 말고, 실제 늦은 획득과
정리 결과를 별도로 관찰한다.

## 검증

- `RedissonCandidateRegistryCancellationTest`: 3개 통과
- `RedissonCandidateRegistryContentionTest`: 1개 통과
- `RedissonStrategicGroupToxiproxyCancellationTest`: 3개 통과
