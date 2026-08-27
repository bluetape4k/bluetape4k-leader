# #720 Exposed provider 증거 검증

이 문서는 `develop`에서 #720 구현 이후 실행하는 릴리스 증거 runbook입니다.
`docs/manual/`은 `0.5.0` release commit에 고정된 버전 문서이므로, 해당 릴리스에
아직 없는 provider marker와 Gradle 입력 계약을 버전 매뉴얼에 소급해 기록하지
않습니다.

## 여섯 provider 순차 검증

`--rerun-tasks`로 각 provider 실행을 강제하고, `set -euo pipefail`로 테스트나
검증 명령이 실패하면 다음 provider로 진행하지 않게 합니다.

```bash
set -euo pipefail

for module in leader-exposed-jdbc leader-exposed-r2dbc; do
  for provider in H2 POSTGRESQL MYSQL_V8; do
    LEADER_TEST_DB="$provider" ./gradlew \
      ":bluetape4k-${module}:test" \
      ":bluetape4k-${module}:koverXmlReport" \
      --no-configuration-cache --no-daemon --rerun-tasks --console=plain

    python3 scripts/ci/validate_provider_artifacts.py \
      --root . --module "$module" --provider "$provider"
    printf 'LEADER_TEST_DB=%s\n' "$provider" | diff -u - \
      "$module/build/test-results/test/leader-test-db.txt"
    printf 'LEADER_TEST_DB=%s\n' "$provider" | diff -u - \
      "$module/build/reports/kover/leader-test-db.txt"
  done
done
```

검증기는 테스트 XML, Kover XML, 두 provenance marker의 존재와 provider 일치를
확인합니다. marker는 선택값과 artifact provenance를 증명하지만 backend assertion을
대신하지 않습니다.

## CI 계약 검증

```bash
python3 scripts/ci/validate_provider_artifacts.py \
  --workflow-contract .github/workflows/ci.yml .github/workflows/nightly-tests.yml
python3 scripts/ci/validate_provider_artifacts.py --self-test
```

CI와 Nightly의 각 provider job은 test와 Kover 단계에 같은 canonical provider를
전달하고, provider별 artifact 이름과 marker를 업로드합니다.
