# Issue #606 redirect route metadata 설계 통합 검토

## 검토 범위와 판정

- 대상: `docs/superpowers/specs/2026-08-23-issue-606-redirect-route-design.md`
- 이슈: #606 `feat(leader-spring-boot): redirect-to-leader route metadata policy 추가`
- 기준: #606/#607/#537 live issue, `leader-spring-boot` 현재 source/test,
  Spring Framework forwarded-header 공식 문서, 승인된 설계 보강본
- 최종 판정: `PASS`
- 최종 severity: `P0=0, P1=0, P2=0, P3=0`
- 다음 gate: 구현 plan 6관점 검토 완료 후 TDD 구현

설계 보강 전 성능·보안·안정성 검토에서 발견된 P1은 모두 문서에 반영했다.
보강 후 재검토 lane이 review-only deadline을 넘긴 경우에는 결과를 성공으로
간주하지 않고 liveness 근거를 남긴 뒤 main-session에서 같은 관점을 재검증했다.

## 관점별 결과

| 관점 | 최신 근거 | 결과 | 통합 조치 |
|---|---|---|---|
| Performance | 초기 native review: P0=0/P1=1/P2=3/P3=1; rerun lane stall 후 main fallback | PASS | `LeaderRouteEvaluation`의 단일 `state()` read, resolver 최대 1회, immutable 설정, bounded scheduler/contract, no quantitative SLA를 고정했다. |
| Security | 초기 native review: P0=0/P1=2/P2=3; rerun lane stall 후 main fallback | PASS | pre-transform raw metadata provider, unknown/untrusted fail-closed, HTTPS/default-443 exact host, raw `@`/fragment/empty-port/control 검사를 고정했다. |
| Stability | 초기 native review: P0=0/P1=3/P2=5; rerun lane stall 후 main fallback | PASS | redirect-only freshness와 fixed clock, cancellation/interruption/Error 경계, caller-owned scheduler, no timeout executor/cache, MVC/WebFlux parity를 고정했다. |
| Operator/Ops | 최종 native review: P0=0/P1=0/P2=0/P3=0 | PASS | 기존 `LeaderRouteGuardProperties` 4-argument/copy/`Serializable` surface, outer gate, restart rollback, AOT skip-warning 방지, authoritative manual contract 명령, Colima 증거와 고정 reason code를 반영했다. |
| Developer/API | 최종 native reread: P0=0/P1=0/P2=0/P3=0 | PASS | additive resolver/provider API, Serializable context/metadata, exact synthetic default-constructor bridge, 명시적 `LeaderRouteEvaluation.kt`, factory overload와 public KDoc를 대조했다. |
| User/caller | 최종 native reread: P0=0/P1=0/P2=0/P3=0 | PASS | 고정 0.5.0 release manual 비변경 경계, MVC `addPathPatterns`, WebFlux path wrapper, YAML, CUSTOM `validUntil`, resolver-only relative fallback과 raw metadata 생산 경계를 반영했다. |
| Main integration | 본 문서와 최신 spec read-back, writer/audit/diff 검증 | PASS | 관점 간 충돌·문서/운영/호환성 누락을 제거하고 최신 요구사항 추적성을 확인했다. |

## 보강 및 해소 이력

| 최초 finding | 보강 내용 | 최신 상태 |
|---|---|---|
| 상태 조회가 resolver context에서 소실됨 | 내부 `LeaderRouteEvaluation`에 decision과 동일 `LeaderState?`를 보존하고 요청당 `state()` 1회로 고정 | 해소 |
| raw forwarded trust 근거가 transformed request에서 재구성될 위험 | pre-transform metadata provider와 `null` absolute fail-closed 규칙 추가 | 해소 |
| HTTP/임의 port/userinfo/fragment/open redirect 위험 | exact ASCII host, HTTPS, default 443, raw authority/fragment/control negative matrix 추가 | 해소 |
| stale/null lease 의미가 기존 `Allowed`와 충돌할 위험 | redirect eligibility에만 fixed-clock freshness 적용, 기존 #537 `Allowed` 유지 | 해소 |
| callback 예외·취소·scheduler lifecycle 불명확 | ordinary exception fail-closed, cancellation/interruption/Error 경계, caller-owned scheduler와 no-timeout 계약 추가 | 해소 |
| property compatibility와 outer gate 누락 | 4-argument/copy/`copy$default`/serialization bridge와 `route-guard.enabled` outer gate matrix 추가 | 해소 |
| 호출자 적용 범위·CUSTOM freshness·manual 문서 부족 | route-scoped MVC/WebFlux 예제, YAML, `validUntil` 계약, EN/KO README와 raw metadata 생산 경계 추가; 고정 0.5.0 manual은 비변경으로 유지하고 1.0.0 반영을 후속으로 명시 | 해소 |
| 운영 관찰성과 검증 증거 부족 | 고정 reason code/tag 집합과 저장소 exact manual validation 명령 추가 | 해소 |

## Main-session 검증 증거

- 설계 파일 read-back: 전체 section, API/property/acceptance/traceability 확인
- `node /Users/debop/.codex/skills/bluetape-writer/scripts/audit-korean-terms.mjs docs/superpowers/specs/2026-08-23-issue-606-redirect-route-design.md`: `findings=0`
- `git diff --check`: 통과
- 초기 `./gradlew --no-configuration-cache :bluetape4k-leader-spring-boot:test`: `BUILD SUCCESSFUL` (baseline cache evidence; 구현 완료 증거 아님)
- native rerun stall lifecycle: performance/security/stability lane 각각 liveness `suspect_stall` 및 `lane_blocked`, main fallback 기록
- API lane: interrupt 후 liveness/stall/block 기록, main fallback 수행
- user lane: 최종 native reread PASS, ops lane: 최종 native reread P0/P1=0, API lane: 최종 native reread PASS

## Source ledger

- Live issue: GitHub `bluetape4k/bluetape4k-leader#606`
- Related live issues: #537 passive guard, #607 request-scoped lease exclusion, Epic #700
- Local anchors: `leader-spring-boot/src/main/kotlin/.../route/`, 기존 MVC/WebFlux route tests,
  `LeaderProperties` compatibility constructors
- Official references:
  - https://docs.spring.io/spring-framework/reference/web/webmvc/filters.html
  - https://docs.spring.io/spring-framework/reference/web/webflux/reactive-spring.html
- Preserved research: `bluetape4k-wiki/research/2026-08-23-spring-forwarded-header-redirect-policy.md`
- Release manual pin: `docs/manual/manifest.yaml` `releaseRef=0.5.0`,
  `releaseCommit=721a9a3808f67489d2bdb8177734325981c24977`

## Writer gate

| Gate | 결과 | 근거 |
|---|---|---|
| SPW-01 | PASS | live issue/related issue, local source/test, official references와 release pin을 대조했다. |
| SPW-02 | PASS | 관점별 finding, 보강, failure/compatibility/rollback, 최신 판정을 포함했다. |
| SPW-03 | PASS | 한국어 독자용 prose를 사용하고 code/API/URL/status token은 보존했다. |
| SPW-04 | PASS | spec, current source/test, wiki research, workflow lifecycle evidence를 read-back했다. |
| SPW-05 | PASS | 표·링크·경로·code token을 확인하고 terminology audit 및 `git diff --check`를 통과했다. |

설계 gate는 `P0=0/P1=0`으로 종료한다. 고정 release pin의 manual은 새 API claim을
받지 않으며, 1.0.0 release pin 확보 후 별도 manual 반영을 수행한다. 구현은 이
review artifact와 승인된 spec/plan, 그리고
`docs/review/2026-08-23-issue-606-redirect-route-plan-review.md`의
`PASS (P0=0/P1=0)` 판정을 기준으로 시작한다.
