# 문제 537 스프링 루트 가드 검토

## 범위 및 검토 근거

- 분기: `feature/issue-537-spring-route-guards`
- 베이스: `ad024ca9`의 `develop`
- 승인된 아티팩트:
  - `docs/superpowers/specs/2026-07-15-issue-537-spring-route-guards-design.md`
  - `docs/superpowers/plans/2026-07-15-issue-537-spring-route-guards-plan.md`
- 기본 모듈: `leader-spring-boot`
- 계약 변경 지원: 핵심 감사 상태 기능과 로컬, Consul, DynamoDB, Kubernetes Lease, 수신기, 테넌트 범위 및 Micrometer 지원
- 명시적 제외: #606의 리디렉션/ID 메타데이터 및 #607의 요청 경로 임대 획득

최종 구현에서는 `STATE`와 `CUSTOM`를 별도의 권한 모델로 유지합니다. 혼합, 누락, 모호함 및 감사 상태 지원되지 않는 선택은 안정적인 코드로 시작되지 않습니다. 경로 평가는 수동적이고 장애 시 폐쇄되며 본문이 비어 있고 ID가 없으며 기본적으로 비활성화되어 있습니다.

## 성능 및 안정성 검사

| Priority | Surface | Lens | Finding | Resolution / evidence |
|---|---|---|---|---|
| P2 | `StateLeaderRouteAuthority` | performance | `STATE` adds one backend state lookup per guarded request. | Exactly one read is enforced by interaction tests; no cache, retry, acquisition, extension, release, or background worker was added. README guidance requires route-scoped use and records the best-effort cost. |
| P2 | WebFlux adapter | performance/stability | A synchronous authority could block an event-loop thread. | Evaluation is deferred until subscription and offloaded to `boundedElastic`; tests prove handler subscription occurs only after `Allowed`. |
| P1, repaired | state capability decorators | correctness/stability | A listener wrapper could advertise audit-state capability while interface bridge defaults discarded `LeaderSlot.leaderId`. | Slot-aware sync, async, suspend, and result overloads now delegate the full slot. Local async and decorated regression tests read the exact audit identity back from state. |
| P1, repaired | MVC/WebFlux adapters | cancellation | Normalizing every throwable would convert cancellation/interruption into a rejection. | Cancellation is rethrown, interruption restores the thread flag, and only ordinary failures become `Unavailable`; pre-evaluation, during-evaluation, and post-subscription tests pass. |

새로운 재시도 루프, 공유 캐시, 무제한 버퍼, 임대 변형, 감시 또는 요청 소유 리소스가 추가되지 않았습니다. 최종 성능/안정성 결과: P0=0, P1=0.

## 사양 및 계획 검증

| Requirement | Implementation and proof | Status |
|---|---|---|
| Disabled default | Conditional auto-configuration and disabled-context tests | PASS |
| Strict `STATE` / `CUSTOM` separation | Explicit mode selector and mixed/missing/ambiguous startup matrix | PASS |
| Audit-state capability | Conservative Core default, capable backend declarations, preserving decorators, startup and constructor invariants | PASS |
| Passive default authority | One `state(lockName)` read, exact audit ID comparison, strict mock verification | PASS |
| MVC semantics | Route-scoped interceptor, one handler invocation only for `Allowed`, empty bounded status response | PASS |
| WebFlux semantics | Deferred/offloaded filter, no rejected handler subscription, cancellation preservation | PASS |
| Java interoperability | Java authority returning `null` is normalized to `Unavailable` and rejected | PASS |
| Configuration selection | Explicit, unique, and primary elector/authority selection tests prove which candidate is used | PASS |
| Documentation parity | English/Korean README sections cover identical modes, errors, statuses, capability limits, process-incarnation identity, and caveats | PASS |
| Diagram | Existing Spring architecture SVG and 2x PNG show exclusive authority inputs and shared route adapters | PASS |
| Scope discipline | Redirect and request-path acquisition remain in #606/#607; no module, BOM, publishing, or workflow change | PASS |

## 독립적 리뷰 융합

첫 번째 독립 코드 검토에서는 Java-null 처리, 지원되지 않는 상태 폴백, 명시적 Bean 유형 오류, 서블릿 클래스 경로 조건화, 선택 방지 테스트 및 취소/중단 적용 범위에 대한 변경을 요청했습니다. 첫 번째 아키텍처는 명시적 상태 계약으로 뒷받침되지 않는 차단된 기능 클레임을 추가로 통과하고 프로세스 ID 재사용에 대해 경고하며 내장된 Bean 이름 충돌 경로를 찾았습니다. 그 발견은 복구되었습니다.

이후의 아키텍처 델타 검토에서는 나머지 P1가 하나 발견되었습니다. 리스너 데코레이터는 기능 플래그를 위임했지만 슬롯 인식 실행 방법은 위임하지 않았습니다. 회귀 테스트에서는 먼저 동기화, 비동기 및 일시 중지 상태의 기본 노드 ID를 재현했습니다. 수신기 및 로컬 비동기 경로가 복구되었으며 공개 `StateLeaderRouteAuthority` 구성이 기능적으로 안전해졌습니다.

최종 독립 결과:

| Lane | Verdict | P0 | P1 | P2 |
|---|---|---:|---:|---:|
| Code review | APPROVE | 0 | 0 | 0 |
| Architecture review | CLEAR | 0 | 0 | 0 |

두 최종 패스는 모두 읽기 전용이었습니다. 슬롯 ID, 비동기 결과 분류, 알림 카디널리티, 취소 동작, 생성자 적용 및 공개 API 호환성을 독립적으로 검증했습니다.

## 6개 렌즈 최종 검토

| Lens | P0 | P1 | P2 | Integrated result |
|---|---:|---:|---:|---|
| Performance | 0 | 0 | 0 | One uncached state read is explicit and tested; WebFlux offloads synchronous evaluation. |
| Stability | 0 | 0 | 0 | Invalid configurations fail at startup; cancellation/interruption and listener identity are preserved. |
| Security | 0 | 0 | 0 | Rejections expose no leader ID, location, exception, or body; occupancy alone never authorizes. |
| Operator/Ops | 0 | 0 | 0 | Stable error codes, safe status set, supported backend list, and rollback-by-disable are documented. |
| Developer/API | 0 | 0 | 0 | APIs are additive; STATE is the default, CUSTOM is an explicit SPI, and mixing is an error. |
| User/caller | 0 | 0 | 0 | MVC/WebFlux usage, route scope, process-incarnation identity, and non-atomic caveats are aligned in both locales. |

## 다이어그램 증거 원장

| Check | Result |
|---|---|
| SVG XML validation | PASS |
| Connector audit | PASS: markers=4, connectors=5, cards=17, intrusions=0, crossings=0 |
| Geometry audit | PASS: `geometry_failures=0` |
| Endpoint audit | PASS: 1 file |
| Mixed-corner audit | PASS: failures=0; this SVG uses no path-level `Q` bends |
| Raster pair | PASS: SVG 1320x1360, PNG 2640x2720, 2x, sRGB |
| Full-size visual review | PASS: text fits, all arrowheads/connectors are visible, and the authority band remains distinct from the woven execution path |

## 새로운 검증

| Command / gate | Result |
|---|---|
| Core full test | PASS, 713 tests |
| Consul full test | PASS, 64 tests |
| DynamoDB full test | PASS, 30 tests |
| Kubernetes unit test | PASS, 13 tests |
| Kubernetes K3s integration test | PASS, 21 tests |
| Micrometer full test | PASS, 76 tests |
| Spring Boot full test | PASS, 422 tests |
| Spring Boot AOT test | PASS, 6 tests |
| Spring Boot module build | PASS |
| Root Detekt command | PASS command; root task reports `NO-SOURCE` and is not claimed as Kotlin source coverage |
| Diagram audits and full-size review | PASS |
| `git diff --check` | PASS |

하나의 이전 Core 실행과 하나의 이전 Spring 실행이 모든 테스트 사례를 완료했지만 Gradle는 `in-progress-results-generic.bin`를 잃었습니다. 두 실행 모두 검증 증거로 인정되지 않았습니다. 종료 코드 0이 관찰된 격리된 재실행은 위에 나열된 결과입니다.

`Unexpected classifier: "#"` 및 기존의 해결되지 않은 KDoc 경고로 인해 리포지토리 기준이 failure하기 때문에 `dokkaGenerateHtml`는 계속 사용할 수 없습니다. 깨끗한 `develop`에서도 동일한 오류가 재현되었습니다. 컴파일, 테스트, AOT 및 모듈 빌드는 이 변경 사항에 대해 허용되는 API 문서 검증입니다.

최종 검토 결과: `PASS`; P0=0, P1=0, P2=0.
