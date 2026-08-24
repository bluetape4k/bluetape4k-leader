# Issue #607 요청별 lease 구현 계획 독립 검토

## DoD 보고

승인된 option 2 명세와 실행 계획을 동일한 파일 SHA로 고정해 여섯 관점과
leader 통합 관점에서 검토했다.

| 검토 관점 | 대상 lane | 계획 SHA | P0 | P1 | 최종 |
|---|---|---|---:|---:|---|
| 성능 | performance | `907c3b05c92874e7ba6cdd95b7870b68f3a87355b89ecd169b8a195ba80d6010` | 0 | 0 | PASS |
| 공개 API | developer/API | `907c3b05c92874e7ba6cdd95b7870b68f3a87355b89ecd169b8a195ba80d6010` | 0 | 0 | PASS |
| 안정성 | stability | `907c3b05c92874e7ba6cdd95b7870b68f3a87355b89ecd169b8a195ba80d6010` | 0 | 0 | PASS |
| 호출자 계약 | user/caller | `907c3b05c92874e7ba6cdd95b7870b68f3a87355b89ecd169b8a195ba80d6010` | 0 | 0 | PASS |
| 보안 | security | `907c3b05c92874e7ba6cdd95b7870b68f3a87355b89ecd169b8a195ba80d6010` | 0 | 0 | PASS |
| 운영 | operator/Ops | `907c3b05c92874e7ba6cdd95b7870b68f3a87355b89ecd169b8a195ba80d6010` | 0 | 0 | PASS |

### 통합 확인

- canonical acquire tuple, residual cap, late handoff와 terminal cleanup 경계가 서로
  충돌하지 않는다. 이미 reservation을 보유한 late handoff는 cap을 재검사하지 않고,
  reservation 없는 신규 admission만 기존 `LEADER_ROUTE_LEASE_ADMISSION_REJECTED`로
  종료한다.
- residual invariant 오류는 새 public error나 metric tag를 만들지 않고
  `LeaseObservationCode.BACKEND_ERROR`의 sanitized 내부 관찰로 제한한다.
- shutdown은 단일 coordinator가 소유하는 단방향 state graph이며, WebFlux caller
  `LeaderSlot` identity/fingerprint, suspend ABI, `ExtendOutcome.Rejected` migration,
  physical scheduler와 logical serial lane, benchmark row 범위가 계획에 고정됐다.
- 기존 `STATE`/`CUSTOM`, legacy status payload, caller-owned scheduler와
  `LeaderLease` serialization 경계의 보존 조건을 확인했다.

### 문서 검증

- Korean terminology audit: `findings=0`
- trailing whitespace: 0
- 실제 placeholder(`TODO`, `TBD`, `FIXME`, `PLACEHOLDER`): 0
- 계획 `git diff --check`: 통과
- 계획 전체 readback: 통과

### 판정

**PASS** — 계획의 P0/P1 차단 항목이 없고, 구현으로 진행할 수 있다.

구현 코드, Gradle test/build, CI, exact-head PR 검증은 구현 단계의 별도 DoD이며
이번 계획 검토에서는 실행하지 않았다.

## 내가 해야 할 일

없음. 구현 전 계획 검토에 필요한 승인과 실행 권한이 이미 확인됐다.

## 다음 단계

1. 이 리뷰 artifact, 승인된 명세, 실행 계획을 Korean Lore commit으로 기록한다.
2. core public contract와 lifecycle RED 테스트부터 시작하고, core/local contract가
   green이 되기 전에는 backend 또는 Spring 구현으로 진행하지 않는다.
3. 구현 후 targeted test, detekt, binary compatibility, manual contract, CI와
   exact-head evidence를 순서대로 수집한다.
