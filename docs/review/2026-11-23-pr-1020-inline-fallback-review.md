# Leader PR #1020 인라인 fallback 리뷰

## 대상과 범위

- 저장소: `bluetape4k/bluetape4k-leader`
- PR: [#1020 중앙 catalog와 Leader 1.2.0 개발선을 일치시킨다](https://github.com/bluetape4k/bluetape4k-leader/pull/1020)
- 기준 branch: `develop`, base SHA `88a0dd1642ab19a05c125f6b11b11f7ca42f5ace`
- 검토 범위: `.github/workflows/ci.yml`, `CHANGELOG.md`, `README.md`, `README.ko.md`, `WIP.md`, `gradle.properties`, `settings.gradle.kts`
- production Kotlin source, public API, dependency set, module topology는 변경하지 않는다.

## 근거

- 안정 배포: GitHub Release `1.1.0`은 Latest이며 tag가 `88a0dd1642ab19a05c125f6b11b11f7ca42f5ace`를 가리킨다. Leader BOM `1.1.0` POM은 Maven Central에서 HTTP 200이다.
- 개발 버전: `gradle.properties`는 `baseVersion=1.2.0`, 빈 `snapshotVersion`을 사용한다. Nightly 게시 workflow만 `-PsnapshotVersion=-SNAPSHOT`을 주입한다.
- 공통 catalog: Dependencies commit `7a5995fd46031ce1faf16337fa08d954436f8f9a`는 tag `catalog/2026-11-22-01`이 가리키는 immutable commit이다. 해당 catalog는 Projects/Exposed를 `2.2.0-SNAPSHOT`, AWS/Graph/Image/JaVers/Leader/Text를 `1.2.0-SNAPSHOT`으로 관리한다.
- 교차 저장소 정렬: 8개 consumer 저장소의 `settings.gradle.kts`와 CI가 모두 같은 catalog SHA를 사용한다. 중앙 release checklist는 게시 순서를 Projects → Exposed → AWS → Graph → Image → JaVers → Leader → Text → Dependencies BOM으로 고정하며, 모든 하위 라이브러리의 개발 버전 게시를 확인한 뒤 중앙 BOM을 마지막에 처리한다.
- 후속 PR: Text #353은 `d7e62b339937e6a443271746002853c30a7764a5`에서 OPEN이고 required checks가 성공했다. Dependencies #291은 `46ab8a42f20a2db2020cf0dae7244506ca6cf0a4`에서 OPEN이며 하위 라이브러리의 개발 버전 게시를 확인한 뒤 마지막으로 처리한다.
- 문서 링크: 영문·한국어 1.1 매뉴얼 기본 경로와 backend connectivity 가이드 경로가 모두 HTTP 200이다.
- 리뷰 기준: 최신 hosted CI run `45122769751`은 기존 PR head `3ba7570fb84d738b57ff810d75039be9598f8549`에서 실행됐다. `leader-dynamodb`가 Kover `0.9.11` agent POM을 받는 과정에서 Maven Central HTTP 429를 다섯 차례 받아 실패했다. 해당 테스트 본문은 실행되지 않았고, 당시 다른 matrix test jobs는 성공했다. 로컬에서 같은 POM은 HTTP 200이므로 이 결과를 코드 실패나 성공으로 해석하지 않는다. WIP와 리뷰 보고서가 반영된 새 head의 전체 CI가 별도로 필요하다.

## 검토 결과

| 영역 | 결과 | 근거 |
|---|---|---|
| 버전 경계 | PASS | `baseVersion=1.2.0`, 빈 `snapshotVersion`, README의 `1.2.0` 개발선과 `1.2.0-SNAPSHOT` 게시 표기가 일치한다. |
| catalog·게시 순서 | PASS | Leader의 settings/CI와 8개 consumer가 동일한 immutable SHA를 사용하며, Leader 다음 Text, 마지막 Dependencies 순서가 checklist와 일치한다. |
| README·manual | PASS | 안정판 링크가 배포된 1.1 매뉴얼을 가리키고, 확인한 4개 공개 경로가 모두 HTTP 200이다. |
| WIP 사실관계 | PASS | 기준일을 `2026-11-23 KST`로 맞췄다. #854–#859가 모두 종료됐고 기존 milestone은 `1.1.0`임을 확인해 미해결 이슈와 존재하지 않는 `1.2.0` milestone 문장을 제거했다. 이전 전환 기록 #235는 종료된 선행 기록으로 표시했다. |
| API·ABI·lifecycle·security | PASS | production source나 API/의존성 변경이 없다. |
| P0/P1 | 0건 | 현재 diff에서 병합 차단 결함을 찾지 못했다. |
| P2/P3 | 0건 | WIP의 stale milestone·issue 상태를 수정했고 남은 비차단 항목이 없다. |

독립 architecture review는 공통 catalog/version/order 근거를 확인한 뒤 `CLEAR`로 판정했다. Independent code-reviewer lane은 `codex-code-mode-host` 실행 파일 부재로 시작하지 못했다. 따라서 이 결과는 독립 code-reviewer attestation이 아니라 workspace의 승인된 inline fallback review다.

## 남은 검증 경계

- 최종 변경 head의 hosted CI와 최신 review/thread read-back은 아직 완료되지 않았다. 이 항목은 merge gate에서 별도로 통과해야 한다.
- 이 문서의 최종 verdict와 report digest는 workflow receipt에서 최종 repository head에 결합한다.

Inline verdict: PASS; P0=0, P1=0.

## 작성 확인

- SPW-01: 대상, 독자, 한국어 문서 범위, source ledger와 미완료 hosted CI를 고정했다.
- SPW-02: 검토 범위, 근거, severity, disposition, residual gate, verdict를 기록했다.
- SPW-03: Korean technical register와 naturalness checklist를 적용했다.
- SPW-04: GitHub release/issue/PR, catalog tag/ref, CI log, Maven Central URL과 중앙 checklist에 각 사실을 대조했다.
- SPW-05: 최종 Markdown을 다시 읽고 표·링크·verdict·DoD를 확인했다.
