# #1007/#1008 CI coverage·Gitleaks 결정성 계획

## 범위와 승인

Milestone `1.1.0`의 #1007과 #1008 후속 수정이다. 기준은
`origin/develop`의 `d2f4a556fe8b9acbd4580faae92b58f0df5cce68`, 작업 branch는
`fix/issue-1007-1008-ci-hardening`이다. 사용자가 승인한 범위는 CI/검증
maintenance surface만 수정하는 것이며 production API·ABI·runtime behavior는
변경하지 않는다. #1006의 backend cancellation 테스트 수정은 다른 worktree에
격리되어 있다.

## 조사 결과와 계약

### #1007 — Kover matrix 집계

Full Nightly `43110046685`의 `coverage-all` artifact를 직접 확인한 결과
Exposed core/JDBC/R2DBC가 H2·PostgreSQL·MySQL artifact로 각각 반복된다. 현재
집계기는 XML path에서 module만 추출해 모든 counter를 합산하므로 동일
module/source set의 instruction denominator를 세 번 더한다.

집계 key는 `(module, source-set/report kind)`로 고정하고 artifact identity는
후보 provenance로 보존한다. 같은 key의 matrix report는 instruction denominator가
같아야 하며, 그중 instruction coverage가 가장 높은 report를 deterministic하게
대표값으로 선택한다. 동률이면 artifact/path 사전순 첫 항목을 선택한다.
denominator가 다르면 조용히 버리지 않고 fail-closed 오류로 처리한다. 출력에는
선택한 artifact를 함께 표시해 `TOTAL`이 고유 module/source-set 대표값의 합이라는
규칙을 드러낸다. expected-artifact 누락·malformed XML 오류 정책은 유지한다.

### #1008 — Gitleaks 설치·history scan

현재 `releases/latest` API 조회와 `--no-git` tree-only scan은 각각 download
drift/외부 504와 commit history leak 사각지대를 만든다. Gitleaks `v8.30.1`
Linux x64 archive URL과 SHA-256을 고정하고, installer가 explicit bounded retry,
checksum 검증, 실패 시 non-zero 종료를 수행하게 한다. CI는 `gitleaks git`과
`--log-opts="--all"`로 history를 검사하며 `--no-git`을 사용하지 않는다.

기존 history의 의도하지 않은 `generic-api-key` false positive 두 건은
redacted `.gitleaks-baseline.json`에 fingerprint로 고정한다. baseline은 새
finding을 허용하는 우회가 아니며, 추가 후 제거된 secret fixture가 history
scan에서 검출되는 계약 테스트를 둔다.

## 순서와 검증

1. [완료] exact head, live issue, Full Nightly artifact 구조, Gitleaks release
   asset/checksum과 현재 workflow/script를 확인한다.
2. [완료] #1007 중복 report와 #1008 historical findings를 로컬 도구로
   재현했다. 구현 전 receipt와 mutation scope를 고정한다.
3. [완료] aggregator에 logical identity grouping, deterministic representative
   선택, denominator mismatch fail-closed를 추가하고 duplicate/malformed/
   missing-artifact 테스트를 먼저 작성한다.
4. [완료] pinned Gitleaks installer script, redacted baseline, history scan
   workflow 변경과 static/mock/history 계약 테스트를 추가한다. installer mock은
   retry 2회 후 성공과 3회 실패·설치 우회 불가를 모두 검증한다.
5. [완료] Python 계약 테스트, aggregator self-test, `git diff --check`,
   workflow YAML/actionlint 가능 여부를 확인한다. CI dispatch는 변경 branch가
   원격에 없으므로 로컬 검증 후 별도 delivery 승인까지 보류한다.
6. [완료] 두 issue에 Korean evidence comment를 read-back하고, PR·merge·issue
   close는 이 계획 범위에 포함하지 않는다.

## 기대 DoD

- Coverage summary가 module/source-set당 한 행과 선택 artifact를 출력하고,
  unique representative counter로 `TOTAL`을 계산한다.
- 동일 denominator가 아닌 report는 fail-closed이며 expected artifact 누락과
  malformed report도 계속 실패한다.
- Gitleaks install version/SHA/URL이 immutable하고 retry budget이 제한되며,
  checksum 또는 download 실패가 required job을 우회하지 않는다.
- Full history scan과 historical baseline이 동시에 동작하고 removed-secret
  fixture가 검출된다.
- CI workflow/static 계약, Python self-tests, diff 검증 결과가 fresh evidence로
  기록된다.

실패하면 해당 계약 테스트부터 다시 실행한다. coverage를 단순 합산하거나
Gitleaks를 `latest`·tree-only·continue-on-error로 되돌리는 수정은 허용하지
않는다.
