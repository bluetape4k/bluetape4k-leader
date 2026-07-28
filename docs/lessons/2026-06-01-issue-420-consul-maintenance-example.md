# Issue #420 Consul 유지 관리 예

## 맥락

Milestone `0.3.0`에는 리더 전용 서비스 유지 관리/드레인 동작을 보여주는 실행 가능한 Consul 예제가 필요했습니다.

## 결정

호출자가 소유한 `ConsulEndpoint` 및 기존 `ConsulServer.Launcher.consul` Testcontainers 도우미와 함께 `ConsulLeaderElector`를 사용합니다. Spring/Ktor 표면을 도입하는 대신 예제를 일반 애플리케이션 모듈로 유지하세요.

## 결과

`examples/consul-maintenance`, README 로케일 쌍, 루트 README 항목, repo-local 모듈 목록, Gradle 설정 등록, CI 경로 필터/작업 및 예제 워크플로 매트릭스 범위가 추가되었습니다.

## 검증

- `./gradlew projects`
- `./gradlew :examples:consul-maintenance:test`
- `./gradlew :examples:consul-maintenance:run`
- `actionlint .github/workflows/ci.yml .github/workflows/examples.yml`
- `git diff --check`
- `rg -n "@Synchronized|synchronized\\s*\\(" examples/consul-maintenance/src/main`

## 향후 지침

백엔드별 예제 모듈의 경우 실행 가능한 시나리오를 작게 유지하고 보류된 잠금과 건너뛴 경쟁자로 경합을 증명합니다. 항상 동일한 PR의 설정, README 로캘 쌍, repo-local AGENTS, CI 및 예제 워크플로를 통해 새 예제를 연결하세요.
