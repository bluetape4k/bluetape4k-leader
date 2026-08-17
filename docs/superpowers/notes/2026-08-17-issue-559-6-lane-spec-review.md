# Issue #559 lease-extension observation 사양 6-lane fresh review

## 범위와 판정

- 대상: [Issue #559](https://github.com/bluetape4k/bluetape4k-leader/issues/559) 사양서
  `docs/superpowers/specs/2026-08-17-issue-559-lease-extension-observation-design.md`
- train: `OBS-02`, 기준 head: `42f42ffa6df4d2906a4312fc9b7acb14d75439e9`
- worktree: `.worktrees/epic-obs-02-extension-observation`
- 범위: production code와 README를 수정하지 않은 사양 단계의 계약·경계·acceptance review
- 최신 독립 lane: 3개 reviewer가 fresh 재실행되었고, 각 reviewer가 아래 2개 lane을 담당해 6개 관점을 모두 판정했다.
- 최종 판정: `CLEAR` — P0=0, P1=0, P2=0

## 6-lane 결과

| lane | 검토 초점 | 최신 결과 | 근거와 disposition |
| --- | --- | --- | --- |
| Architecture / API | additive core facade, public JVM surface, data/value modeling, context source | `CLEAR` | Event/context를 일반 immutable non-`Serializable` class로 고정하고 explicit equality와 redacted `toString`을 정의했다. `@JvmStatic`은 object member로 고정하고 `hasObservers`/`publish`는 `@JvmSynthetic internal` bridge 및 Java/bytecode fixture로 검증한다. |
| Security / privacy | raw identity, exception cause, token, log exposure, reflection boundary | `CLEAR` | `BackendError.cause`는 callback-time reference로만 유지하고 기본 observer/Micrometer가 저장·직렬화하지 않는다. Event/Context `toString()`은 bounded 값만 출력하며 exception detail, token, lock/leader identity를 제외한다. synthetic bridge는 security boundary가 아님을 문서화했다. |
| Performance / concurrency | zero-allocation path, snapshot linearization, bounded dispatch, fairness | `CLEAR` | 모든 detailed/watchdog boundary의 `hasObservers()` guard, COW array reference read 선형화, false→add 누락 및 true→remove 불필요 allocation 허용, global 1024/per-registration 256 permits, non-blocking drop/warning을 고정했다. |
| Stability / operations | callback failure, fatal error, lifecycle, scheduler rejection, shutdown | `CLEAR` | 일반 `Exception`은 task 안에서 격리하고 `Error`는 재전파한다. scheduler admission rejection과 delegate-thrown `RejectedExecutionException`을 분리하고, accepted task-after-close, permit `finally`, Spring context destroy/multi-context 중복·누수 테스트를 acceptance로 고정했다. |
| Developer / Kotlin / testing | Kotlin API idiom, cancellation, parity, ABI/fixture quality | `CLEAR` | blocking/suspend detailed 경로와 watchdog source/execution parity, `CancellationException` 재전파, public facade `javap`/Java fixture, `copy`/`componentN` 부재, `@JvmSynthetic` 비노출, callback redaction/error tests를 acceptance에 포함했다. |
| User / docs / compatibility | Issue acceptance, Korean technical register, README/manual, train compatibility | `CLEAR` | #529와 #559 관계, source/outcome/redaction/cancellation/watchdog 규칙, all-README 정확한 #559 stale marker scan, manual EN/KO 경로, milestone/labels/assignee/PR DoD를 명시했다. unrelated Issue #74 marker는 scan에서 제외한다. |

## 수리된 review findings

이전 fresh pass에서 발견된 P1/P2는 사양을 다시 열어 모두 반영했다.

- public `data class` generated JVM/serialization ambiguity → 일반 immutable class와 explicit surface로 변경
- `hasObservers()`와 COW snapshot race → guard는 최적화 힌트, delivery membership은 `publish`의 array reference read에서 선형화
- facade member-extension 표기 → 실제 `object` member `@JvmStatic` 선언으로 고정
- `publish(event)` zero-allocation 모순 → caller guard 뒤에서만 event/context를 생성하는 pseudo-contract 추가
- scheduler admission와 delegate-thrown `RejectedExecutionException` → blocking/suspend 별도 matrix와 contract test 추가
- callback `Throwable` 격리 → 일반 `Exception`만 격리하고 fatal `Error`는 재전파
- README scan false positive/negative → 모든 tracked README에서 `issue #559`와 stale 표현이 결합된 정확한 regex로 교체
- Spring global registry lifecycle → context destroy, 다중 context 중복/누수, accepted callback-after-close 검증 추가
- event/context `toString()` raw 노출 → bounded redaction contract와 회귀 테스트 추가
- shared `ObservationRegistry` dedup ambiguity → process-global `internal object` manager, identity map, single-lock acquire/ref-count/last-close linearization과 race acceptance 추가
- shared registry option-conflict redaction risk → 추가 observer나 조용한 재사용 없이 `IllegalStateException` fail-fast와 충돌 회귀 테스트 추가

## 검증 증거

- 사양 문서 `git diff --check`: PASS
- 사양 문서 미완료 표식 검색: 0건, PASS
- Markdown fence parity: 2개, PASS
- source anchor 존재 검증: `LockExtender`, `ExtendOutcome`, `ExtendDelegate`, `LeaderLeaseAutoExtender`, `LeaderLockHandle`, `LockStateHolder`, `LockHandleElement`, `LeaderElectionListener`, Micrometer recorder 모두 PASS
- baseline 영향 모듈 테스트(사양 작성 전): `./gradlew :bluetape4k-leader-core:test :bluetape4k-leader-micrometer:test --no-daemon --no-configuration-cache --console=plain` — 77 tests, BUILD SUCCESSFUL
- 최신 review lane은 사양-only read-only 범위로 Gradle을 재실행하지 않았다.

## 구현 전 잔여 상태

- 실제 README에는 #559 stale 문구 4건이 남아 있다. 이는 사양 결함이 아니라 구현 단계 acceptance의 의도된 `PENDING` 항목이다.
- 계획 검토에서 지적된 경계를 반영해 `droppedCount()`를 누락된 observer delivery 수로 고정하고, Micrometer 구현 class/name/tag 위치, NOOP 조건, `AutoCloseable` destroy ownership, Java `Duration` wrapper, examples README까지의 stale scan 범위, shared registry의 process-global dedup/ref-count/lock 및 옵션 충돌 fail-fast를 implementation plan에 명시했다.
- production code, README, commit, PR은 아직 변경하지 않았다. implementation plan은 사양 승인 후 작성되어 별도 승인 대기다.
- 사용자 사양은 승인되었고, implementation plan은 별도 승인 대기다.
