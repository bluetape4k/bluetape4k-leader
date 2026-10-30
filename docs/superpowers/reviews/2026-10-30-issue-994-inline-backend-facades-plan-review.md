# #994 backend inline facade 실행 계획 통합 검토

## 검토 범위와 기준

- 승인 설계: [`2026-10-30-issue-994-inline-backend-facades-design.md`](../specs/2026-10-30-issue-994-inline-backend-facades-design.md)
- 검토 계획: [`2026-10-30-issue-994-inline-backend-facades-plan.md`](../plans/2026-10-30-issue-994-inline-backend-facades-plan.md)
- 저장소 기준: `bluetape4k/bluetape4k-leader`, `develop`/작업 기준 `ad4110a92afb0c3f40bc0e84e7e9da1e6b61c97f`
- live GitHub 확인: #994 OPEN, milestone `1.1.0`; PR #992 MERGED, merge commit `ad4110a92afb0c3f40bc0e84e7e9da1e6b61c97f`
- 사용자 승인: 설계 방향과 실행 계획 승인 완료. 구현 범위는 facade 36개 전환, `etcdClientOf` inline 유지다.
- 이 검토는 계획 문서에 한정한다. Kotlin source/test 변경이나 production test 실행은 없다.

## 독립 6 관점 결과

| 관점 | 최초 finding | 계획 반영 | 최종 판정 |
|---|---|---|---|
| 성능 | P2 — callback adapter가 제거되고 소비자 호출 경로가 facade로 연결되는지 검증 부족 | 계획 131행에서 consumer `INVOKESTATIC`과 `javap -c -p` facade body를 구조적으로 검사하고 처리량 주장은 배제 | PASS — P0/P1/P2/P3=0 |
| 안정성 | P1 — 취소 전파 및 lock/slot 정리·재획득, P2 — etcd client/executor 소유권, P2 — 실행/skip/teardown 증거 부족 | 계획 67–68행과 160행에 취소·재획득, `try/finally`, JUnit XML 수 및 lifecycle 증거 추가 | PASS — P0/P1/P2/P3=0 |
| 보안 | P2 — 후보에서 기대값을 만들 수 있음, endpoint/credential 격리 부족 | 계획 44행·82–84행은 고정 SHA와 checksum manifest/self-test를 사용하고, 67–68행은 loopback/mock·임시 자격 증명·원격 환경 거부·비밀 로그 금지를 고정 | PASS — P0/P1/P2/P3=0 |
| 운영 | P2 — Colima/Docker 진단 명령과 exact-head hosted CI 수집 명령 부족 | 계획 145행에서 Colima/context/socket 확인을 구체화하고, 173행에서 `gh pr view`, `gh pr checks`, `gh run view --log-failed` 및 skip/retry 기록을 고정 | PASS — P0/P1/P2/P3=0 |
| 개발자/API | P1 — non-local return 예시가 컴파일되지 않을 수 있음; P1 — 37개 source declaration inventory 자동 검증 부족; P1 — default bridge 호출별 기대값 부족; P2 — method metadata 비교 부족 | 계획 44행·70–84행·127행에서 유효한 block-body fixture, 37개 고정 declaration key, explicit/default 72 callsite, `Signature`와 annotation attribute 비교를 명시 | PASS — P0/P1/P2/P3=0 |
| 사용자/호출자 | 추가 차단 finding 없음 | 계획 18–21행은 공개 호출 문법과 시그니처가 유지되는 근거로 문서 변경 N/A를 한정하고, 과거 DynamoDB consumer를 별도로 실행 검증 | PASS — P0/P1/P2/P3=0 |

## 통합 검토

- 모든 설계 acceptance 기준이 실행 순서가 있는 계획 작업과 검증 증거에 연결되어 있다.
- declaration inventory는 기준 HEAD의 37개 고정 target key만 검사한다. `ClientSupport.kt`의 비대상 inline `etcdClient`는 수에 포함하지 않고 변경 금지로 명시했다.
- 34개 current API의 explicit/default 68 callsite와 과거 DynamoDB shim의 4 callsite를 분리해 main/`$default` 선택까지 검증한다.
- JVM 호환성은 owner/name/descriptor/access, generic `Signature`, nullability·declaration·parameter·type annotation attributes, `$default`, 숨김 shim을 각각 비교한다. Kotlin metadata는 승인된 inline/crossinline flag 차이만 허용한다.
- Testcontainers 실행은 모듈별 순차 처리이며, 제품 실패·환경 실패·skip·미실행을 구별한다. PR CI, exact head, 별도 merge 승인 경계가 구체적으로 기술되어 있다.
- 최종 통합 판정: **PASS — P0=0, P1=0, P2=0, P3=0.** 계획 실행을 막는 미해결 항목은 없다.

## Writer DoD

| 체크 | 결과 | 근거 |
|---|---|---|
| SPW-01 — 독자·목적·근거 | PASS | 실행 담당자용 한국어 계획 리뷰이며 승인 설계·계획·live 이슈/PR 상태를 구분했다. |
| SPW-02 — artifact contract | PASS | 6개 독립 관점, 최초 finding의 처분, 통합 판정, 범위와 검증 한계를 기록했다. |
| SPW-03 — 한국어 기술 문체 | PASS | 자연스러운 기술 문장으로 작성했고 코드·경로·명령·식별자는 원문대로 보존했다. |
| SPW-04 — 기술 의미와 추적성 | PASS | 각 finding을 계획의 수정 위치와 최종 증거에 연결했다. |
| SPW-05 — 최종 read-back | PASS | 계획·명세와 함께 재독했고, 용어 audit은 findings=0, 두 artifact의 code fence·공백 검사는 통과했다. |

## 검토 한계

- 이 단계에서는 production code와 테스트를 실행하지 않았다. 구현 단계에서 계획의 RED/GREEN 및 ABI/consumer 증명을 수행한다.
- 계획 승인으로 PR 생성·push 범위는 승인되었지만, merge는 exact PR head의 최종 근거 보고 뒤 새 승인 전까지 보류한다.
