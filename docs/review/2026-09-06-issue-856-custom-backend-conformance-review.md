# Issue #856 custom backend conformance 인라인 리뷰

## 범위와 판정

- 기준: `origin/develop` `5d3eefe4376634f25da5879ac8329764adcc34b3`
- 구현: `abc1c5a2`와 선행 설계 commit `59e53fa3`
- 방식: 사용자 지시에 따른 exact-diff 인라인 검토. 독립 reviewer나 별도 모델 provenance는 주장하지 않는다.
- 최종 판정: `P0=0`, `P1=0`, 미처리 `P2=0`, 미처리 `P3=0`

production source, dependency, module, workflow는 바뀌지 않았다. 공개 변경은
`leader-core`의 published testFixtures에 추가한 conformance API이며 기존 consumer의
production ABI와 source에는 영향이 없다.

## 발견과 처분

| 심각도 | 발견 | 처분 | 재검증 |
|---|---|---|---|
| P1 | 초기 provider 설계가 관리 API로 후보를 강제 삭제할 수 있어 TTL을 구현하지 않은 backend도 expiry 검사를 통과할 수 있었다. | API를 `awaitCandidateExpiration`으로 좁히고 실제 후보 부재나 fake clock의 자연 만료만 허용했다. | blocking/suspend expiry와 refresh no-resurrection 4개 조합 PASS |
| P2 | 최초 lifecycle assertion이 `updateResult` 뒤에도 `lastCompletionTime`이 보존된다고 잘못 가정했다. | refresh 보존 검증과 result 갱신 검증의 순서를 분리했다. | conformance 11/11 PASS |
| P2 | provider의 public cleanup hook은 `close()`와 책임이 겹쳤다. | 중복 hook을 제거하고 fixture가 test마다 `close()`를 정확히 한 번 호출하도록 했다. | testFixtures compile 및 core 전체 PASS |
| P2 | Redisson test는 TTL 0만 확인해 실제 key 삭제 전 refresh가 성공하는 timing window가 있었다. | `RMapCache.get(nodeId) == null`까지 bounded suspend polling한다. | targeted 4/4, module 316/316 PASS |

## 6관점 최종 검토

| 관점 | 판정 | 근거 |
|---|---|---|
| 성능 | P0=0, P1=0 | production hot path 변경 없음. 8 workers x 25 rounds, 25 race rounds, 5초 expiry timeout으로 test 부하가 bounded다. |
| 안정성 | P0=0, P1=0 | actual expiry, refresh/unregister race, lost update, idempotent cleanup, suspend cancellation을 검증한다. test-only `Thread.sleep(5L)`은 custom store의 bounded actual-expiry polling에만 있다. |
| 보안 | P0=0, P1=0 | credential, secret, network input을 새 API에 넣지 않고 provisioning과 client 설정은 provider 소유로 남겼다. |
| 운영 | P0=0, P1=0 | production rollout과 관측 경로는 바뀌지 않는다. fixture가 성능, retry, 장애 복구를 인증하지 않음을 두 README에 명시했다. |
| 개발자/API | P0=0, P1=0 | testFixtures API는 provider와 최소 adapter surface만 additive로 공개한다. 생성된 Gradle metadata에서 test-fixtures variant와 JAR을 확인했다. |
| 사용자/caller | P0=0, P1=0 | 영문·한글 README의 사용 예제와 capability matrix가 일치하며, 존재하지 않는 strategic async/virtual-thread API를 암시하지 않는다. |

## 수용 기준 추적

| Issue #856 수용 기준 | 구현 및 증거 |
|---|---|
| 실행 모델별 최소 계약 | blocking/suspend x single/group 네 strategic mode를 한 fixture가 순회한다. lock 기반 async/virtual-thread는 기존 별도 계약임을 문서화했다. |
| skip/null/result, refresh, expiry, cleanup | winner result와 counter, loser `null`, refresh 보존, actual expiry, no-resurrection, idempotent unregister 테스트 |
| concurrent atomicity/linearizability | register race, refresh/update lost-update, refresh/unregister no-resurrection 테스트 |
| provider-injectable fixture | `StrategicBackendConformanceProvider`와 blocking/suspend adapter |
| custom backend 적용 | built-in Local 구현을 재사용하지 않는 `ConcurrentHashMap` test provider |
| core 문서와 책임 경계 | `leader-core/README.md`, `leader-core/README.ko.md`, Korean KDoc |
| test/detekt/ABI | 아래 검증 결과 |

## 검증 근거

- TDD compile RED: fixture/provider unresolved reference를 확인했다.
- assertion RED: 잘못된 completion-time 기대 때문에 2개 테스트가 실패했고 assertion 경계를 수정했다.
- mutation RED: non-atomic read-modify-write 변형에서 기대 200 대비 55/63으로 2개 테스트가 실패했다.
- mutation GREEN: atomic `computeIfPresent` 복원 후 conformance 11/11 PASS.
- `:bluetape4k-leader-core:test --rerun-tasks`: 1029/1029 PASS.
- Redisson expiry targeted: 4/4 PASS.
- `:bluetape4k-leader-redis-redisson:test --rerun-tasks`: 316/316 PASS.
- `detekt checkBinaryCompatibility`: PASS, ABI artifacts 16, ignored 1, unknown 0, unclassified 0.
- Gradle module metadata: `testFixturesApiElements`와 test-fixtures JAR capability 확인.
- `git diff --check`: PASS.

## 남은 게이트

PR 생성 뒤 exact-head GitHub CI, reviews/threads read-back, mergeability 확인은 `PENDING`이다.
이는 로컬 판정의 PASS로 대체하지 않는다.

## Writer DoD

- SPW-01 PASS: 독자, 기준 commit, 구현 commit, 검토 방식과 provenance 경계를 명시했다.
- SPW-02 PASS: 발견, 처분, 6관점, 수용 기준, 검증, 남은 게이트를 포함했다.
- SPW-03 PASS: Korean naturalness KO-01~KO-06을 확인하고 식별자와 command를 보존했다.
- SPW-04 PASS: 수치와 public API 이름을 source, JUnit XML, Gradle 출력에 대조했다.
- SPW-05 PASS: 표, heading, severity 합계와 남은 `PENDING`을 최종 read-back했다.
