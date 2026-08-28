# Issue #720: Exposed provider별 테스트 증거 캐시 격리 교훈

## 문제

`leader-exposed-jdbc`와 `leader-exposed-r2dbc` 테스트 fixture는
`LEADER_TEST_DB`로 실행 provider를 선택하지만, Gradle `Test` task가 이 환경
값을 입력으로 선언하지 않았다. H2를 실행한 뒤 provider만 바꿔 같은 task를
호출하면 Gradle이 task를 `UP-TO-DATE`로 판단해 H2 결과와 Kover 증거가 남을 수
있었다. CI와 Nightly의 Kover 단계도 provider 환경값을 받지 않아, artifact가
어떤 provider를 검증했는지 확인할 수 없었다.

## 결정

1. Exposed JDBC/R2DBC의 `test`와 `koverXmlReport` task에 정규화한
   `LEADER_TEST_DB`를 입력으로 선언한다. 값이 없으면 `ALL`을 사용해 로컬의
   기존 다중-provider 동작을 유지한다.
2. 각 task가 실행한 provider를 `test-results/test/leader-test-db.txt`와
   `reports/kover/leader-test-db.txt`에 기록한다. marker가 없거나 기대값과
   다르면 CI 검증 단계가 실패한다.
3. CI와 Nightly는 test와 Kover 단계에 같은 provider를 전달하고, 테스트 XML과
   marker를 함께 업로드한다. 여섯 provider fan-out은 정적 계약 validator와
   validator 자체 회귀 테스트로 고정한다.
4. 버전 고정 매뉴얼의 release pin을 유지하기 위해, JDBC/R2DBC의 H2·PostgreSQL·
   MySQL_V8 순차 실행과 두 marker 비교는
   `docs/release/issue-720-exposed-provider-evidence.md`에 release 증거 runbook으로
   기록한다. marker는 provenance 증거이며 backend assertion을 대체하지 않는다.

## 검증

- 구현 전 H2 실행 뒤 `LEADER_TEST_DB=POSTGRESQL`로 같은 JDBC `test` task를
  다시 호출했을 때 `UP-TO-DATE`가 재현됐다.
- 구현 후 JDBC H2와 PostgreSQL 실행에서 task가 다시 실행되고 marker가 각각
  `LEADER_TEST_DB=H2`, `LEADER_TEST_DB=POSTGRESQL`로 갱신됐다.
- 최종 여섯 조합을 `--rerun-tasks`로 실행했다. JDBC는 H2/PostgreSQL/MySQL_V8
  각각 148개, R2DBC는 각각 153개 테스트가 성공했고, 모든 test/Kover marker가
  해당 provider와 일치했다.
- 환경 변수가 없을 때 JDBC 304개와 R2DBC 323개 전체 테스트가 성공했고, 두
  marker가 `LEADER_TEST_DB=ALL`로 기록됐다.
- `scripts/ci/validate_provider_artifacts_test.py`의 positive/mismatch/
  workflow/self-test 계약이 통과했다.
- CI/Nightly workflow contract, `actionlint`, Detekt, 매뉴얼 release contract,
  `git diff --check`도 통과했다.

## 재발 방지

provider를 선택하는 환경 변수, system property, 파일 입력은 실행 task의
Gradle input으로 선언하고, 결과 artifact에는 선택값을 검증 가능한 marker로
남긴다. CI가 생성한 결과를 업로드할 때는 marker를 XML과 함께 보존하며,
provider별 job은 test·report 단계의 환경 전파와 marker 일치를 계약 검사로
확인한다.
