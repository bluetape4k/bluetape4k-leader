# 백엔드 야간 릴리스 게이트 미리보기

이 노트는 미리 보기 리더 백엔드에 대한 릴리스 실행 전 증거를 정의합니다. 그 자체로는 백엔드를 Stable로 승격시키지 않습니다. 백엔드 승격 기준은
`docs/release/preview-backend-stable-promotion.md`에 있습니다.

## 필수 요약

안정적인 릴리스에 태그를 지정하기 전에 'develop'에서 실행되는 최신 전체 `Nightly` 워크플로를 열고 'Preview Backend Release
Summary' 작업 요약을 읽어보세요.

릴리스 준비 상태는 다음과 같습니다.

| Backend | Evidence job | Required result |
|---|---|---|
| Consul | `Test / leader-consul` | `success` |
| DynamoDB | `Test / leader-dynamodb (DynamoDB Local)` | `success` |
| etcd | `Test / leader-etcd (Testcontainers)` | `success` |
| Kubernetes Lease | `Test / leader-k8s (K3s + group slots)` | `success` |

'건너뛰었다'는 실행이 전체 릴리스 검증 야간 실행이 아니었음을 의미합니다. 릴리스 증거로 사용하기 전에 전체 범위로 `Nightly`를 트리거하거나 매주
예정된 전체 실행을 기다립니다.

## 범위 경계

일일 CI는 연기 및 목표 변경 게이트로 남아 있습니다. 테스트 컨테이너가 많은 K3s 런타임 검사는 Nightly 전체를 유지하므로 모든 끌어오기 요청
속도를 늦추지 않고 릴리스 증거가 완료됩니다.
