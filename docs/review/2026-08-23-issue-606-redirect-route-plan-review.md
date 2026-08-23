# Issue #606 redirect route 구현 계획 통합 검토

## 검토 범위와 판정

- 대상 계획: `docs/superpowers/plans/2026-08-23-issue-606-redirect-route-plan.md`
- 기준 사양: `docs/superpowers/specs/2026-08-23-issue-606-redirect-route-design.md`
- 이슈: #606 `feat(leader-spring-boot): redirect-to-leader route metadata policy 추가`
- 범위: 설정/ABI, 공통 URI·proxy 정책, 단일 상태 평가, MVC/WebFlux parity,
  scheduler·cancellation, AOT/manual 운영 증거, README 호출자 적용
- 최종 판정: `PASS`
- 최종 severity: `P0=0, P1=0, P2=0, P3=0`
- 구현 전 stop condition: 충족. 이 문서와 승인된 spec을 기준으로 TDD 구현을 시작한다.

이번 계획 검토에서 발견된 P1은 모두 계획과 사양에 반영했다. 현재 manifest가
`releaseRef=0.5.0`, `releaseCommit=721a9a3808f67489d2bdb8177734325981c24977`에
고정되어 있으므로 versioned manual에는 새 API claim을 추가하지 않고 비변경을
검증한다. 1.0.0 release pin 확보 후 상세 manual을 별도 반영한다.

## 관점별 결과

| 관점 | 최종 근거 | 결과 | 반영 내용 |
|---|---|---|---|
| Performance | native plan review final: P0=0/P1=0/P2=2/P3=0 | PASS | 단일 `state()` read, 고정 시각 캡처, immutable allowlist, bounded scheduler와 동시·재진입 resolver 테스트를 고정했다. |
| Security | native plan review final: P0=0/P1=0/P2=2/P3=0 | PASS | HTTPS exact host/default 443, raw metadata trust, canonical peer, 민감값 비관찰과 URI negative matrix를 고정했다. |
| Stability | native plan review final: P0=0/P1=0/P2=0/P3=0 | PASS | serialization fixture/defaulting, Binder outer-gate 경계, callback cancellation, scheduler ownership, redirect-enabled AOT를 고정했다. |
| Operator/Ops | native final reread: P0=0/P1=0/P2=0/P3=0 | PASS | AOT skip/warning false-green 방지, 실제 fixture 실행 수·policy assertion, `validate_manual_contract.py`, Colima/docker 증거를 추가했다. |
| Developer/API | native final reread: P0=0/P1=0/P2=0/P3=0 | PASS | public context/metadata의 `Serializable`·`serialVersionUID`, 4-argument synthetic default constructor/copy bridge, 명시적 `LeaderRouteEvaluation.kt` 소유권을 고정했다. |
| User/caller | native final reread: P0=0/P1=0/P2=0/P3=0 | PASS | resolver-only/null metadata relative-only fallback, 사전 생성 `PathPattern`, MVC high-priority `Filter`, WebFlux pre-transform decorator/external boundary, EN/KO README와 pinned manual 경계를 고정했다. |
| Main integration | 최신 plan/spec read-back과 writer/audit/diff 검증 | PASS | 파일 소유권, task 순서, acceptance mapping, release-pin 경계를 통합했다. |

## 최초 finding과 해소

| 최초 finding | 해소 내용 | 상태 |
|---|---|---|
| 설정 변경이 Kotlin/JVM ABI와 기존 serialization을 깨뜨릴 수 있음 | 4-argument 일반·synthetic default constructor, `copy`, `copy$default`, legacy fixture/defaulting, round-trip 검증을 Task 1/2에 명시 | 해소 |
| public DTO 직렬화 계약이 계획에만 존재 | spec code block과 plan에 `Serializable`, private `serialVersionUID`, round-trip test를 동일하게 기록 | 해소 |
| evaluation 타입 파일 소유권이 모호함 | `LeaderRouteEvaluation.kt`를 단일 내부 evaluation 소유 위치로 고정 | 해소 |
| AOT task green이 fixture skip을 숨길 수 있음 | skip/warning을 허용하지 않고 실제 redirect-enabled fixture test count와 policy bean/context assertion을 evidence로 요구 | 해소 |
| 고정 release manual과 새 API claim 충돌 | pinned manual 비변경 검증으로 범위를 축소하고 1.0.0 release pin 후 후속 반영으로 분리 | 해소 |
| WebFlux route 예제가 요청마다 pattern을 parse하고 raw producer 순서가 부정확함 | pattern을 filter 생성 시 한 번 parse하며, MVC high-priority `Filter`와 WebFlux pre-transform server/`HttpHandler` decorator 또는 외부 trusted boundary로 경계를 명시 | 해소 |
| manual/컨테이너 검증 증거가 불충분함 | authoritative manual contract 명령과 `colima status`, `docker context show`, `docker info`, 실제 test/skip 결과 기록을 고정 | 해소 |

## 계획·사양 추적성

- Task 1–3: 설정 default/outer gate, public API/serialization, 단일 상태 평가와
  기존 runtime compatibility.
- Task 4–5: MVC/WebFlux 307·`Location` parity, route scope, raw metadata, error/
  cancellation/scheduler 경계.
- Task 6–7: conditional auto-configuration/AOT, EN/KO README·KDoc·metadata와
  pinned release manual non-change guard.
- Task 8: targeted test, module build/AOT, detekt, manual contract, Colima evidence,
  diff/terminology audit와 stop condition.
- `#607` request-scoped lease lifecycle, backend URI 자동 생성, 새 dependency/module/
  workflow/Nightly는 명시적으로 제외한다.

## Main-session 검증 증거

- spec/plan/review read-back 완료.
- spec terminology audit: `findings=0`.
- plan terminology audit: `findings=0`.
- `git diff --check`: 통과.
- 세 untracked Markdown artifact no-index whitespace check: 출력상 whitespace 오류 없음.
- plan review lifecycle: six perspective lane dispatch/startup evidence가 run receipt
  sequence 41–61에 기록되었고, performance/security/stability/ops/API/user 최종 판단은
  모두 P0/P1=0이다. 이전 rerun stall lane은 별도 liveness/block receipt로 보존했으며
  이를 성공으로 오인하지 않았다.
- 변경 경로: native review lane은 모두 read-only였고 코드 파일 변경은 없다.

## Writer gate

| Gate | 결과 | 근거 |
|---|---|---|
| SPW-01 | PASS | Issue/related issue, current source/test, base SHA, worktree, release pin과 제외 범위를 대조했다. |
| SPW-02 | PASS | Task 1–8에 파일, RED→GREEN 테스트, 의존 순서, 명령, 증거와 rollback 경계를 고정했다. |
| SPW-03 | PASS | 한국어 reader-facing plan과 보존해야 할 code/API/URL/status token을 구분했고 terminology audit가 통과했다. |
| SPW-04 | PASS | 승인 spec의 acceptance/DoD를 파일별 mapping, compatibility, AOT/manual 검증으로 추적했다. |
| SPW-05 | PASS | 최신 plan 전체 read-back, placeholder/경로 점검, `git diff --check`와 untracked check를 통과했다. |

계획 gate는 `PASS (P0=0/P1=0)`이다. 다음 단계는 feature worktree에서 테스트를
먼저 추가하고 Task 1부터 순서대로 구현하는 것이다.
