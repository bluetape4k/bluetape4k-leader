# 이슈 531 및 536 봄 운영 검토

## 범위 및 검토 근거

- 분기: `feature/issues-531-536-spring-ops`
- 베이스: `848f7934`의 `develop`
- 승인된 아티팩트:
  - `docs/superpowers/specs/2026-07-15-issues-531-536-spring-operations-design.md`
  - `docs/superpowers/plans/2026-07-15-issues-531-536-spring-operations-plan.md`
- 모듈 슬라이스: `leader-spring-boot`
- 제외된 후속 조치: #533, #537, #602, #603

외부 `codex review --uncommitted` 프로세스는 90초로 제한되었습니다. 저장소 시작 검사 기간을 소비하고 결과 판정 없이 경보를 종료했습니다. Type A 폴백 계약에 따라 메인 세션에서는 6가지 필수 렌즈를 모두 통해 현재 차이를 검토하고 아래 결과를 통합했습니다.

## 4-P 성능 및 안정성 검사 단계

| Priority | File:Line | Lens | Finding | Resolution / evidence |
|---|---|---|---|---|
| P2 | `LeaderElectionReadinessHealthIndicator.kt:31-52` | performance | Readiness cost is linear in JVM-known lock count and backend state latency. | Kept one sequential `state` read per snapshot name, added exact call-count tests, left the contributor disabled by default, and documented bounded static registries. |
| P2 | `LeaderElectionReadinessHealthIndicator.kt:49-50` | stability | Broad exception capture could hide fatal VM errors. | Catch is limited to ordinary `Exception`; failures are isolated per lock and remaining names are still checked. |
| P2 | `LeaderElectionAspect.kt:109-113` | stability | An unconstrained composed-annotation pointcut matched annotation static initialization and broke AspectJ CTW. | Constrained the union to method `execution(* *(..))`; main compilation, aspect tests, full module tests, and AOT tests pass. |

차단/일시 중지 경로, 리소스 소유자, 재시도 루프, 버퍼, 백그라운드 작업자, 캐시 또는 핫 경로 할당이 추가되지 않았습니다. 프로덕션 동시성 스캔에서는 기존 `runCatching` 사이트만 발견했습니다. 변경된 포인트컷에는 새로운 동시성 기본 요소나 예외 캡처가 포함되어 있지 않습니다.

최신 4-P 단계 결과: P0=0, P1=0. 모든 P2 결과는 복구되었거나 명시적으로 제한되었습니다.

## 5단계 스펙 및 계획 검증

| Requirement / task | Implementation and proof | Status |
|---|---|---|
| Readiness `UP`, `OUT_OF_SERVICE`, `DOWN`, empty registry, unknown expiry | `LeaderElectionReadinessHealthIndicator`; deterministic fixed-clock tests | PASS |
| Exactly one state read per known name and no exception disclosure | MockK exact-call verification for success and mixed failure; detail assertion excludes backend exception text | PASS |
| Disabled-by-default bean and duration binding | Separate conditional auto-configuration plus `ApplicationContextRunner` tests | PASS |
| All Spring and leader attributes are aliased | `LeaderScheduled` and merged-annotation tests for every exposed attribute | PASS |
| Existing contention skip and validator behavior | Aspect skip test; invalid SpEL and strict-final-method validator tests | PASS |
| AspectJ CTW compatibility | History-retention main-source dogfood plus module compile/build | PASS |
| English/Korean operations guidance | Both module READMEs cover opt-in setup, status meaning, cost, disclosure, scheduling enablement, and rollback-compatible separate annotations | PASS |
| Scope discipline | One existing module only; no dependency, module, BOM, catalog, workflow, publishing, manual, or generated-artifact change | PASS |

계획 작업 1-6이 완료되었습니다. 승인되지 않은 범위 변경이 없으며 알려진 유효성 검사 공백도 없습니다. 릴리스 매뉴얼은 매니페스트가 0.4.0에 고정되어 있으므로 의도적으로 변경되지 않은 상태로 유지됩니다.

5단계 판정: `PASS`.

## 6-R단계 6개 렌즈 검토

| Lens | P0 | P1 | P2 | P3 | Integrated result |
|---|---:|---:|---:|---:|---|
| Performance | 0 | 0 | 0 | 0 | Linear cost is opt-in, tested, and documented; no cache or extra round trip was introduced. |
| Stability | 0 | 0 | 0 | 0 | Per-lock failures are isolated, fatal errors are not swallowed, and CTW/AOT evidence is green. |
| Security | 0 | 0 | 0 | 0 | Exception text is excluded; raw lock-name disclosure and Actuator access policy are documented. |
| Operator/Ops | 0 | 0 | 0 | 0 | Status semantics, JVM-local boundary, readiness-group inclusion, and dynamic-name warning are explicit. |
| Developer/API | 0 | 0 | 0 | 0 | APIs are additive; aliases reuse Spring/core defaults and existing validation/election paths. |
| User/caller | 0 | 0 | 0 | 0 | Both README locales explain scheduling enablement, normal Spring constraints, and the separate-annotation fallback. |

기본 세션 통합도 검증되었습니다.

- `LeaderScheduled` KDoc는 영어이고 소스 속성과 일치합니다.
- 기존 `@Scheduled`와 `@LeaderElection` 호출자는 계속 호환됩니다.
- 아직 출시되지 않은 0.5.0 추가 작업에는 CHANGELOG 또는 마이그레이션 노트가 필요하지 않습니다. README 적용 범위는 수동 매니페스트가 진행될 때까지 할당된 릴리스 노트 표면입니다.
- 모듈 등록, CI/Nightly, Kover, BOM/카탈로그, 종속성, 벤치마크 및 워크플로 YAML을 위한 저장소 위험 게이트는 해당 표면이 변경되지 않았기 때문에 N/A입니다.
- Testcontainers 종료는 예상되는 늦은 Mongo/Lettuce 모니터 메시지를 생성했지만 전체 모듈 실행에서는 372/372 테스트를 통과하여 `BUILD SUCCESSFUL`를 종료했습니다.

최신 6-R 단계 결과: P0=0, P1=0.

## 새로운 검증

| Command | Result |
|---|---|
| Targeted five-class Spring test selection | PASS, 65 tests |
| `./gradlew :bluetape4k-leader-spring-boot:test --no-configuration-cache --console=plain` | PASS, 372 tests |
| `./gradlew :bluetape4k-leader-spring-boot:build --no-configuration-cache --console=plain` | PASS, including 5 AOT tests |
| `./gradlew detekt --no-configuration-cache --console=plain` | PASS command, root task reported `NO-SOURCE`; not treated as source-analysis coverage |
| `git diff --check` | PASS |

최종 검토 결과: `PASS`; P0=0, P1=0. PR 전달은 승인된 범위를 벗어납니다.
