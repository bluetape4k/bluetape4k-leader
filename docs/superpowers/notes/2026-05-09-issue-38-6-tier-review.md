# 6-Tier 코드 리뷰 — Issue #38 minLeaseTime

- **Issue**: #38
- **작성일**: 2026-05-09
- **범위**: core options, local blocking/async/virtual-thread/suspend 최소 lease 보유

## 1. API / 호환성

- `LeaderElectionOptions.minLeaseTime`과 `LeaderGroupElectionOptions.minLeaseTime`을 기본값 있는 마지막 파라미터로 추가했다.
- 기존 constructor 호출은 유지된다.
- validation은 `minLeaseTime >= 0`, `minLeaseTime <= leaseTime`을 강제한다.

## 2. 정확성 / 타이밍

- local blocking/async/virtual-thread 경로는 공통 helper에서 unlock/release 직전 남은 최소 시간을 `LockSupport.parkNanos`로 보장한다.
- local async는 기존처럼 executor thread가 lock을 보유하고 `action().join()`을 수행하므로 동일 helper 적용으로 동작한다.
- group은 semaphore release 전 대기하므로 `maxLeaders=1` 기준 lockAtLeastFor 동작을 검증했다.

## 3. 백엔드 일관성

- 분산 backend는 옵션 필드만 받으며 unlock TTL 위임은 #77 범위로 남긴다.
- #77에서 Redis/Mongo/Hazelcast/ZooKeeper/Exposed backend별 unlock semantics를 storage TTL 기반으로 구현해야 한다.

## 4. 코루틴 / 취소

- suspend single/group은 action 완료 또는 예외 후 `NonCancellable` cleanup context에서 남은 최소 시간을 `delay`한 뒤 unlock/release한다.
- cancellation 중에도 release 순서를 유지한다.

## 5. 테스트 / 커버리지

- option validation tests 추가.
- local single/group blocking min hold tests 추가.
- local suspend single/group min hold tests 추가.
- focused tests: 65 passing
- `:leader-core:test`: 288 passing
- `:leader-core:koverXmlReport`: line coverage 498/565 = 88.1%
- `compileKotlin`: 전체 모듈 compile 통과

## 6. 문서 / 유지보수성

- root README / Korean README option 예시를 갱신했다.
- `leader-core` README / Korean README에 `minLeaseTime` 의미와 #77 scope split을 명시했다.
- `MinLeaseTimeSupport`를 내부 helper로 분리해 blocking/suspend remaining 계산을 공유한다.

## 결론

PR 생성 가능. 이 PR은 #38의 core/local reference behavior를 닫고, distributed backend TTL 위임은 #77에서 이어서 처리한다.
