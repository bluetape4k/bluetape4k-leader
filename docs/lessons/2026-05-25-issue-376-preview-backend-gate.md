# Issue 376 미리보기 백엔드 릴리스 게이트

## 맥락

Milestone 0.2.2는 미리 보기 Consul, DynamoDB, etcd 및 Kubernetes Lease 백엔드를 승격합니다. 릴리스 게이트는 어떤 런타임 지원 테스트 레인이 패치 릴리스를 보호하는지, 그리고 Kubernetes가 다른 미리보기 백엔드와 다른 이유를 보여주기 위해 필요했습니다.

## 결정

PR CI 수표 이름을 안정적으로 유지하되 워크플로 주석에 게이트를 직접 문서화하세요. Consul, DynamoDB Local 및 etcd는 PR CI 및 Nightly 모두에서 모듈 `test` 작업을 실행합니다. Kubernetes는 PR CI에서 기본 `test` 작업을 유지하는 반면 권한 있는 K3s `k8sTest` 작업은 Nightly full로 유지됩니다.

K3s 오류가 전체 릴리스 게이트에 failure하도록 Nightly 적용 범위 및 최종 상태 집계에 K3s Nightly 전체 작업을 포함합니다.

Consul 통합 테스트에서는 `ConsulServer.Launcher.consul`를 사용합니다. DynamoDB Local 및 etcd는 이미 표준 Testcontainers CI 환경을 사용했으므로 Consul CI 및 Nightly 작업은 이제 동일한 `TESTCONTAINERS_RYUK_DISABLED`, `DOCKER_HOST` 및 4 GiB Gradle 힙 설정을 사용합니다.

## 결과

0.2.2 미리보기 백엔드 게이트는 `.github/workflows/ci.yml` 및 `.github/workflows/nightly-tests.yml`에서 명시적입니다. K3s는 권한 있는 Docker/K3s 동작이 필요하기 때문에 빠른 PR 런타임 게이트의 일부가 아닙니다. 대신 기존 Nightly 전체 경로로 보호됩니다.

## 검증

- `actionlint .github/workflows/ci.yml .github/workflows/nightly-tests.yml`
- `./gradlew :bluetape4k-leader-consul:test :bluetape4k-leader-dynamodb:test :bluetape4k-leader-etcd:test --no-daemon`
- 로컬 및 고속 PR 레인은 권한 있는 Docker/K3s 가용성을 가정할 수 없기 때문에 K3s 런타임 검증은 Nightly full에 위임됩니다.
