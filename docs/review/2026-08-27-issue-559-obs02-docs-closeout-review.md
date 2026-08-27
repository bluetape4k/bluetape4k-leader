# Issue #559(OBS-02) 문서 closeout 통합 검토

## 검토 범위와 판정

- 대상: Issue #559 `feat(leader-core): lease-extension observation hook 추가`와
  Epic #699의 이미 병합된 core·Micrometer·Spring 구현을 설명하는 문서 closeout
- 변경 범위: root·module EN/KO README, Prometheus dashboard example README,
  `docs/manual/drafts/` 미배포 초안 EN/KO, lesson
- 기준: 현재 `develop` source/test, 승인된
  `docs/superpowers/specs/2026-08-17-issue-559-lease-extension-observation-design.md`,
  `docs/superpowers/plans/2026-08-27-issue-559-obs02-docs-closeout-plan.md`, live
  Issue #559/Epic #699
- release 경계: `docs/manual/manifest.yaml`의 `0.5.0`/
  `721a9a3808f67489d2bdb8177734325981c24977` pinned manual은 변경하지 않음
- 최종 로컬 판정: **PASS (P0=0, P1=0, P2=0, P3=0)**
- 다음 gate: Lore commit, PR metadata, exact-head hosted CI. Merge는 fresh exact-head
  승인 전까지 보류한다.

구현 source나 workflow·BOM·manifest·example source는 변경하지 않았다. README와
초안은 현재 `develop` API를 설명하지만 미배포 경고를 유지한다.

## 변경 경계와 source ledger

| 영역 | 문서 근거 | source 확인 |
|---|---|---|
| Core event | root·`leader-core` README, draft | `LeaderLeaseExtensionObserver.kt`, `ExtendOutcome.kt`, `LockExtender.kt`, `LeaderLeaseAutoExtender.kt` |
| Adapter | `leader-micrometer` README, draft | `MicrometerObservationLeaderLeaseExtensionObserver.kt`, `LeaderObservationOptions.kt` |
| Spring lifecycle | `leader-spring-boot` README, draft | `LeaderObservationAutoConfiguration.kt`, `LeaseExtensionObservationRegistrationManager.kt` |
| Example | `examples/prometheus-dashboard/README*` | `PrometheusDashboardApp.kt`의 `context.name?.startsWith("leader.")` predicate |
| Release/manual | draft frontmatter·promotion gate, lesson | `docs/manual/manifest.yaml`, release inventory/validator |

## 관점별 결과

| 관점 | 독립 검토 결과 | 해소·통합 근거 |
|---|---|---|
| Performance | PASS, P0/P1/P2/P3=0 | in-flight admission만 global 1024/per-registration 256으로 bounded임을 명시하고 registration 수·callback fan-out은 애플리케이션 책임으로 분리했다. 고정 core capacity를 adapter 설정처럼 안내하지 않는다. |
| Stability | PASS, P0/P1/P2/P3=0 | JDK 25 toolchain, core event와 Micrometer Observation 이름의 차이, scope mismatch, cancellation/Error eventless 경계, watchdog delegate rejection의 force-stop, close/late delivery를 source와 맞췄다. |
| Security/Privacy | PASS, P0/P1/P2/P3=0 | lock/leader는 opt-in sanitisation, `BackendError.cause`는 raw exception으로 남는다는 사실과 custom observer 책임, `includeExceptionDetails`의 `Observation.error(...)` 경계를 명시했다. |
| Operator/Ops | PASS, P0/P1/P2/P3=0 | `Rejected`를 watchdog reservation·user bounded queue full/timeout으로 설명하고 timeout 뒤 queued command의 지연 실행 가능성과 caller-side `elapsedNanos`를 기록했다. registry delivery 전용 cumulative `droppedCount()`와 baseline/delta 진단, Spring parent switch를 추가했다. |
| Developer/API | PASS, P0/P1/P2/P3=0 | delegate가 `Exception`을 던질 때 publish 후 rethrow하고 반환된 `BackendError`는 outcome으로 반환한다는 구분, fail-open context(lock name + `auditLeaderId=null`), #529 acquire/execution 대 #559 terminal extension 경계를 고정했다. blocking 예제와 suspend `extendActiveLockDetailedSuspend(...)`를 분리했다. |
| User/Caller | PASS, P0/P1/P2/P3=0 | 각 module dependency 예제에 `0.4.0`/pinned `0.5.0`과 미배포 `develop` 경고를 넣었다. `@LeaderElection`·`@LeaderGroupElection`·direct body의 active scope, WATCHDOG는 single-leader `autoExtend=true`만, group auto-extension 없음, registration lifetime을 안내했다. |
| Main integration | PASS | 초기 six-lens findings를 문서에 보수하고 EN/KO token·code-block/link parity, stale scan, writer audit, manual/test 결과를 read-back했다. |

## 발견 항목과 보수 이력

| 최초 finding | 보수 내용 | 상태 |
|---|---|---|
| registry boundedness를 전체 fan-out 상한으로 오해할 위험 | bounded 범위를 in-flight permit으로 한정하고 registration/fan-out 운영 책임을 명시 | 해소 |
| Spring README가 모든 identity/exception을 sanitise한다고 오해할 위험 | lock/leader sanitiser와 raw `BackendError.cause`/exception detail opt-in을 분리 | 해소 |
| JDK·관찰 이름·scope·watchdog rejection 경계 누락 | 현재 toolchain과 source predicate/classifier를 README/draft에 반영 | 해소 |
| `Rejected`와 `droppedCount()` 의미 혼동 | watchdog/user queue rejection과 observer delivery drop을 별도 신호로 기록 | 해소 |
| queue timeout 뒤 지연 실행 및 `elapsedNanos` 의미 누락 | queue full/timeout, delayed command, caller-side duration을 추가 | 해소 |
| fail-open context 및 BackendError throw/return 오기 | lock name 유지/leader ID null, throw와 return semantics를 명시 | 해소 |
| blocking API를 suspend scope에서 복사할 위험 | blocking snippet임을 표시하고 suspend variant를 별도 안내 | 해소 |
| release version과 example handler 오인 위험 | 모든 영향 README에 미배포 경고, Prometheus exact-name opt-in caveat 추가 | 해소 |

## Writer gate (SPW-01..05)

| Gate | 결과 | 근거 |
|---|---|---|
| SPW-01 | PASS | live issue/Epic, current source/test, release pin과 변경 경계를 대조했다. |
| SPW-02 | PASS | 초기 finding, 수정 내용, compatibility·shutdown·rollback 경계를 기록했다. |
| SPW-03 | PASS | reader-facing prose는 한국어로 작성하고 code/API/URL/status token은 보존했다. |
| SPW-04 | PASS | root·module EN/KO README, unreleased draft, example, lesson을 source와 read-back했다. |
| SPW-05 | PASS | link·stale·locale parity, Korean terminology audit, `git diff --check`를 통과했다. |

## 검증 증거

- `git diff --check`: PASS
- `node scripts/check-readme-language-switches.mjs`: `groups=37; files=74; failures=0`
- changed Markdown relative-link 검사: `files=13; failures=0`
- stale README/draft marker scan: 결과 없음
- EN/KO contract token·code-block parity: 6 pairs PASS
- Korean terminology audit: 8 files, `findings=0`
- release inventory: `Release inventory written: 35 projects.`
- `ruby scripts/manual/validate_manuals.rb ...`: `Manuals are aligned.`
- `ruby scripts/manual/validate_release_manuals.rb ...`: `442 checked, 0 missing`
- Ruby manual tests: `37 runs, 392 assertions, 0 failures, 0 errors, 0 skips`
- Core targeted tests: `SUCCESS: Executed 90 tests in 8.3s`, `BUILD SUCCESSFUL`
- Micrometer targeted tests: `SUCCESS: Executed 5 tests in 1.2s`, `BUILD SUCCESSFUL`
- Spring targeted tests: `SUCCESS: Executed 22 tests in 6.6s`, `BUILD SUCCESSFUL`
- module detekt (`core`, `micrometer`, `spring-boot`): `BUILD SUCCESSFUL`

## 후속 위험과 stop condition

현재 문서는 process-local·non-durable 관찰 계약만 설명한다. remote exporter 수락,
cross-process deduplication/replay, Prometheus demo의 자동 lease meter 노출은
주장하지 않는다. `0.5.0` pinned manual 승격과 release version 변경은 별도 promotion
gate다. PR exact head의 hosted CI와 mergeability를 확인하기 전에는 merge하지 않는다.
