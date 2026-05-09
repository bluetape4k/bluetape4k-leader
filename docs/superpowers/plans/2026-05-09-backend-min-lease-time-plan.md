# Backend minLeaseTime 구현 계획

## Phase 1. AOP option 복구

- 두 annotation에 `minLeaseTime: String = "PT0S"`를 추가한다.
- `PT0S`가 유효하도록 non-negative duration parser로 `minLeaseTime`을 parsing한다. Negative value는 계속 실패해야 한다.
- `LeaderElectionOptions` / `LeaderGroupElectionOptions`에 값을 전달한다.
- Annotation, aspect, validator, cache-key test를 갱신한다.

## Phase 2. 공통 release helper

- #38에서 만든 leader-core remaining-duration helper를 재사용한다.
- Backend module이 time arithmetic을 복제하지 않도록 helper visibility를 조정한다.
- Lock-level release method를 추가해 즉시 unlock하거나 남은 최소 lease 시간만큼 backend TTL을 유지한다.

## Phase 3. Backend 구현

- Lettuce single lock: Lua에서 remaining min lease에 따라 `DEL` 또는 `PEXPIRE`를 선택한다.
- Mongo single/group slot lock: `deleteOne` 또는 `updateOne(expireAt = now + remaining)`을 선택한다.
- Hazelcast single/group slot lock: `remove` 또는 현재 token을 remaining TTL로 다시 set한다.
- Exposed JDBC/R2DBC single/group slot lock: `deleteWhere` 또는 `lockedUntil` update를 선택한다.
- Redisson single lock: key TTL update 또는 normal unlock을 선택한다.

## Phase 4. Test와 문서

- Repository에 이미 infrastructure coverage가 있는 backend에 fast-completion test를 추가한다.
- Single/group AOP annotation mapping test를 추가한다.
- README의 backend TTL delegation 설명을 갱신한다.
- Affected module test, compile, 가능한 범위의 Kover를 실행한다.
- 6-Tier review를 기록하고 `feat/38-min-lease-time` base의 stacked draft PR을 만든다.
