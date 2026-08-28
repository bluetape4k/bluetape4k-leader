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
- 각 attempt의 lease는 30초로 제한해 짧은 registry 작업이 watchdog 없이도 소유권을
  유지하게 하고, caller 취소 시 원본 bounded future는 취소하지 않는다.
- 취소와 실제 획득이 경합하면 기존 상태 기계가 late acquisition을 관찰해 bounded
  `unlockAsync` cleanup을 예약한다.
- Toxiproxy 회귀 시험은 owner를 attempt window보다 오래 유지한 뒤 해제하고, 잔여
  waiter 없이 새 logical thread가 즉시 재획득하는지 확인한다.

## 예방 규칙

실패한 가정: coroutine 취소가 caller를 반환시키면 backend lock waiter도 곧 끝난다.

발견 증거 또는 교정: Redisson `lockAsync`는 owner가 유지되는 동안 pub/sub waiter를
계속 보유하며, future cancellation과 서버의 실제 lock 획득 시점은 어긋날 수 있다.

수정 결정: 무기한 `lockAsync` 대신 bounded `tryLockAsync` 시도와 late-acquisition
cleanup을 함께 사용한다. P1 review 지적은 기존 green CI보다 우선하는 blocker로
취급하고, owner-held integration 경계를 추가한다.

향후 예방 확인: suspend distributed lock 대기 경로를 추가하거나 바꿀 때는 bounded
wait, logical thread ownership, cancellation race, owner-held cleanup, unlock 응답
timeout을 mock과 실제 Redis/Toxiproxy에서 각각 검증한다.

## 검증

- `RedissonCandidateRegistryCancellationTest`: 2개 통과
- `RedissonStrategicGroupToxiproxyCancellationTest`: 3개 통과
