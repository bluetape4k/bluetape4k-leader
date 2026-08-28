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
- 취소와 실제 획득이 경합하면 기존 상태 기계가 late acquisition을 관찰해 bounded
  `unlockAsync` cleanup을 예약한다.
- Toxiproxy 회귀 시험은 취소 후 500ms attempt window 안에 owner를 해제해 실제 late
  acquisition 경합을 만들고, 취소된 waiter의 정리 뒤 새 logical thread가 bounded 시간에
  재획득하는지 확인한다.

## 예방 규칙

실패한 가정: coroutine 취소가 caller를 반환시키면 backend lock waiter도 곧 끝난다.

발견 증거 또는 교정: Redisson `lockAsync`는 owner가 유지되는 동안 pub/sub waiter를
계속 보유하며, future cancellation과 서버의 실제 lock 획득 시점은 어긋날 수 있다.

수정 결정: 무기한 `lockAsync` 대신 watchdog을 유지하는 bounded `tryLockAsync` 시도와
source future 취소, late-acquisition cleanup을 함께 사용한다. P1 review 지적은 기존
green CI보다 우선하는 blocker로 취급하고, non-completing source와 owner-held 실제
late-acquisition integration 경계를 추가한다.

향후 예방 확인: suspend distributed lock 대기 경로를 추가하거나 바꿀 때는 bounded
wait, watchdog lease, source cancellation deadline, logical thread ownership, cancellation
race, owner-held cleanup, unlock 응답 timeout을 mock과 실제 Redis/Toxiproxy에서 각각
검증한다.

## 검증

- `RedissonCandidateRegistryCancellationTest`: 3개 통과
- `RedissonStrategicGroupToxiproxyCancellationTest`: 3개 통과
