# refresh는 등록이 아니라 존재하는 후보의 갱신이다

## 맥락

Issue [#847](https://github.com/bluetape4k/bluetape4k-leader/issues/847)는 네 strategic interface의 기본 `refreshCandidate`가 없는 후보를 다시 등록해 heartbeat가 zombie 후보를 부활시키는 문제를 확인했다.

## 놓친 가정과 근거

후보가 없을 때 전달받은 `info`를 등록해도 된다는 기본 구현의 가정이 `registerCandidate`와 `refreshCandidate`의 책임을 섞었다. blocking/suspend, single/group custom fixture 네 개가 missing 후보를 재등록하는 동작을 RED로 재현했다.

## 결정

- missing 또는 expired 후보의 refresh는 no-op으로 종료한다.
- 존재하는 후보만 갱신하며 `registeredAt`, 실행 시각, 성공/실패 횟수를 보존한다.
- metadata와 요청 TTL만 변경한다.
- 구체 backend는 가능하면 원자적 refresh를 제공하고, 기본 구현은 semantic fallback으로 유지한다.

## 결과와 검증

커밋 `655c1253`에서 네 기본 구현과 custom fixture를 같은 계약으로 맞췄다. core 전체 1,000개 테스트와 `detekt`, binary compatibility 검사가 통과했다.

## 재발 방지

등록, refresh, heartbeat API를 검토할 때 missing 상태의 의미를 수용 기준에 명시한다. 기본 메서드와 backend override를 함께 비교하고, missing, expired, existing 상태에서 등록 여부와 상태 보존을 각각 테스트한다. list 후 register로 구현한 기본 경로는 원자성을 보장하지 않으므로 backend override의 동시성 계약을 별도로 검증한다.
