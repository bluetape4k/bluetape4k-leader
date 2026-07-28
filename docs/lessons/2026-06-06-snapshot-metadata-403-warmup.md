# 2026-06-06 - 스냅샷 메타데이터 403 준비

## 맥락

Issue #497에 대해 수동으로 전달된 예제 워크플로는 두 K8s 예제 작업이 모두 통과했지만 `examples-tenant-aggregator`가 `bluetape4k-exposed-r2dbc-tests:1.11.0-SNAPSHOT`용 HTTP 403을 사용하는 중앙 SNAPSHOT 메타데이터에서 failure했음을 입증했습니다. failure한 작업 재시도가 통과되어 테스트 회귀가 아닌 일시적인 메타데이터 조회 문제가 검증되었습니다.

## 결정

중앙 SNAPSHOT 재시도 처리를 메타데이터 검증 failure만 재시도하는 전용 준비 스크립트로 이동합니다. CI, 예제 및 Nightly 워밍업 범위를 확장하여 Exposed 테스트 픽스처와 기존 Ktor SNAPSHOT 소비자를 포괄합니다.

## 결과

Ktor 및 Exposed SNAPSHOT 좌표는 종속 테스트가 실행되기 전에 워밍업됩니다. Central이 메타데이터 403을 반환하면 이미 시작된 테스트 작업을 다시 실행하는 대신 준비 단계에서 재시도가 발생합니다.

## 검증

- `bash -n .github/scripts/retry-snapshot-warmup.sh`
- `actionlint .github/workflows/ci.yml .github/workflows/examples.yml .github/workflows/nightly-tests.yml`
- `git diff --check`
- GitHub 예제는 failure한 `examples-tenant-aggregator` 작업을 다시 실행한 후 27055610835를 실행합니다. 첫 번째 failure는 중앙 메타데이터 403이었으며 두 K8s 작업은 모두 재시도 전에 통과되었습니다.

## 향후 지침

bluetape4k SNAPSHOT 아티팩트를 사용하는 테스트 모듈을 추가하는 경우 테스트 매트릭스 재시도 루프를 사용하기 전에 해당 `compileTestKotlin` 작업을 관련 워밍업 범위에 추가하세요.
