# 구현 계획 — minLeaseTime (#38)

- **Issue**: #38
- **작성일**: 2026-05-09
- **Spec 참조**: `docs/superpowers/specs/2026-05-09-min-lease-time-design.md`

---

## 작업 목록

### T1. Options 확장

- `LeaderElectionOptions.minLeaseTime` 추가
- `LeaderGroupElectionOptions.minLeaseTime` 추가
- validation: non-negative, `<= leaseTime`
- option tests 갱신

### T2. Local blocking/group helper

- `AbstractLocalLeaderElector.tryWithLeaderLock`에서 acquire 후 start time 기록
- finally에서 unlock 전 min hold sleep
- `AbstractLocalLeaderGroupElector.tryWithPermit`도 동일하게 slot release 전 sleep

### T3. Local suspend helper

- `LocalSuspendLeaderElector`에서 acquire 후 start time 기록
- finally에서 `withContext(NonCancellable) { delay(remaining) }` 후 unlock
- `LocalSuspendLeaderGroupElector`도 동일 적용

### T4. Tests

- options validation test
- local single/group min hold test
- suspend single/group min hold test
- exception path min hold test

### T5. 문서

- root README / Korean README option 예시 갱신
- `leader-core` README / Korean README option 예시 갱신
- #77과의 scope split 명시

### T6. 검증 / 리뷰

- `git diff --check`
- `./gradlew compileKotlin --continue`
- `./gradlew :leader-core:test`
- `./gradlew :leader-core:koverXmlReport`
- 6-Tier review 기록

### T7. PR

- draft PR 생성
- #38 link
- #77 후속 범위 명시
