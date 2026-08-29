# Issue #741 설계 사양 통합 리뷰

## 검토 범위

- 사양: `docs/superpowers/specs/2026-08-29-issue-741-spring-observation-scope-design.md`
- 기준 소스: `LeaderLeaseExtensionObservers`, `LeaderLeaseAutoExtender`, lease adapter, Spring AOP, observation registration manager와 관련 테스트
- 독립 렌즈: Performance, Stability, Security, Operator/Ops, Developer/API, User/caller
- 통합 기준: 공개 ABI 보존, cross-registry identity 0건, fail-closed direct call, lifecycle/admission/performance evidence, 운영 문서와 rollback

## 초기 findings와 처리

| 우선순위 | 렌즈 | Finding | 처리 | 재검토 |
|---|---|---|---|---|
| P1 | Performance | 전체 registration 순회와 mismatch drop 오염 가능성 | wildcard/capability identity bucket, O(1) `hasObservers`, matching-only admission과 1024 in-flight stress 계약 추가 | CLEAR |
| P1 | Performance | scope 설치의 할당·지연 근거 부재 | capability별 context element 재사용, 기존 benchmark 확장과 15% median evidence 기준 추가 | CLEAR |
| P1 | Stability | Reactor operator와 adapter 전파 경계 불명확 | coroutine bridge 보장 범위, non-suspend operator 제외, lease adapter capture/restore와 close-race 테스트 추가 | CLEAR |
| P1 | Stability | context별 immutable owner/lifecycle 불명확 | fixed owner bean, manager canonical capability, close/reopen revoke 계약 추가 | CLEAR |
| P1 | Security | caller-supplied `Any` scope spoofing 가능성 | private-constructor capability를 registration이 생성하고 arbitrary token 입력 API를 제거 | CLEAR |
| P1 | Operator/Ops | manual provenance와 rollout/rollback/runbook 부재 | #559 EN/KO draft delta, canary/disable/binary rollback/graceful shutdown 절차 추가 | CLEAR |
| P1 | Operator/Ops | registry selection/conflict 진단 부재 | Spring single-candidate/`@Primary`, parent/child ownership, 비식별 복구 메시지 계약 추가 | CLEAR |
| P1 | Developer/API | Kotlin module 간 `internal` bridge 불가와 기존 bridge descriptor 충돌 | public `@JvmSynthetic` opaque bridge exact shape와 기존 overload 보존 계약 추가 | CLEAR |
| P1 | Developer/API | aspect/auto-config ABI와 wiring 순서 불명확 | 기존 constructor와 5/6-인자 factory descriptor 유지, owner lookup 후 internal 연결 명시 | CLEAR |
| P2 | User/caller | direct-call migration과 aspect별 실행 모델 불명확 | before/after migration, global observer privacy 경고, single/group 지원 행렬 추가 | FIXED |
| P2 | Security | raw exception opt-in의 exporter 노출 위험 | 기존 정책임을 명시하고 operator/exporter redaction 책임 및 A→B 0건 검증 추가; library redaction은 별도 hardening으로 defer | ACCEPTED |
| P2 | Stability | revoke와 active action close 경쟁 불명확 | revoke 뒤 automatic 0회/global 1회, nested restore, reopen-new-capability 선형화 테스트 추가 | FIXED |

## 통합 판단

- 공개 `LeaderLeaseExtensionEvent` 5-인자 constructor와 기존 global observer 계약을 유지한다.
- registry object를 dispatch token으로 사용하지 않고 manager entry가 소유한 capability로 격리한다.
- 기존 unscoped `hasObservers()`/`publish(event)` descriptor를 보존하고 새 cross-module bridge만 additive synthetic surface로 추가한다.
- explicit global observer는 의도적으로 모든 event를 받고 global admission을 공유한다. 다른 scoped registration은 mismatch traffic의 permit/drop을 소비하지 않는다.
- direct elector와 Reactor non-suspend operator callback은 automatic attribution 범위 밖이며 fail-closed다.
- versioned manual의 `releaseRef`와 `releaseCommit`은 변경하지 않고 미출시 변경은 #559 draft에만 기록한다.

최신 집계는 P0=0, P1=0이다. P2는 모두 spec에 반영하거나 근거를 남겨 defer했으며 구현을 막는 미결정은 없다.

## 구현 계획 6-lane 검토

| 렌즈 | 초기 판정 | 계획 보완 | 최신 판정 |
|---|---|---|---|
| Performance | P1 1, P2 1 | no-observer 사전 종료, baseline `f44b7c6`/candidate exact head, 3-fork JSON·median·allocation gate | P0=0, P1=0, CLEAR |
| Stability | P1 2, P2 1 | close/bucket 제거 선형화와 weak-reference test, child-local aspect, partial rollback 단위 | P0=0, P1=0, PASS |
| Security | P1 2, P2 1 | public ambient accessor 제거, local-only owner, 기본 raw-error suppression negative test | P0=0, P1=0, CLEAR |
| Operator/Ops | P1 1, P2 2 | startup-only kill switch와 재시작 smoke, no-wait shutdown semantics, benchmark/exact-head evidence gate | P0=0, P1=0, CLEAR |
| Developer/API | P1 2, P2 1 | coordinator 3-인자 ABI 보존, context-local selector, Java synthetic negative compile/javap proof | P0=0, P1=0, PASS |
| User/caller | P1 0, P2 1 | caller-owned scope의 자기 observer 전용 의미, close 수명, Spring 비연결, Java 제약 문서화 | P0=0, P1=0, COMMENT resolved |

계획 최신 집계도 P0=0, P1=0이다. 모든 P2는 task, test, 문서 또는 명시적 기존 계약으로 처리되었고 구현 착수 blocker가 없다.

## Writer gate

- SPW-01: PASS — 구현자, 리뷰어, 운영자가 필요한 결정과 근거를 한국어로 정리했다.
- SPW-02: PASS — 초기 finding, 처리, 재검토, 통합 판단을 분리했다.
- SPW-03: PASS — 기술 식별자를 보존하고 중복 문장을 제거했다.
- SPW-04: PASS — 각 finding을 사양의 구체적 계약과 독립 렌즈 결과에 연결했다.
- SPW-05: PASS — P0/P1 수렴, P2 disposition, 공개 ABI와 문서 경계를 최종 read-back했다.

## Step DoD

- A-03 설계 승인 및 spec review: PASS
- 6개 독립 렌즈: PASS
- 영향 렌즈 재검토: PASS
- 최신 P0/P1: 0/0
- 구현 계획 진행 가능: YES
