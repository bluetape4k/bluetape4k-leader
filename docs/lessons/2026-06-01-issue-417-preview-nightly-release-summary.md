# 이슈 417 Preview Nightly 릴리스 요약

## 맥락

미리 보기 백엔드 릴리스 증거가 Consul, DynamoDB, etcd 및 Kubernetes Nightly 작업에 분산되어 릴리스 전 검토를 놓치기 쉽습니다.

## 결정

GitHub Actions 단계 요약에 미리보기 백엔드 릴리스 게이트 테이블을 작성하는 전용 Nightly 요약 작업을 추가합니다. DynamoDB, etcd 및 K3s 검사를 전체 Nightly로 유지하고 건너뛴 결과를 태그 지정 전에 전체 Nightly를 실행하라는 신호로 처리합니다.

## 결과

이제 릴리스 검토자는 일일 CI 범위를 변경하지 않고도 미리 보기 백엔드 준비 상태에 대한 하나의 간략한 요약을 갖게 됩니다.

## 검증

- `git diff --check`
- `actionlint .github/workflows/nightly-tests.yml`

## 향후 지침

다른 미리보기 백엔드를 추가할 때 해당 전체 런타임 작업을 요약 작업 및 `docs/release/preview-backend-nightly-gate.md`의 릴리스 게이트 노트에 추가하세요.
