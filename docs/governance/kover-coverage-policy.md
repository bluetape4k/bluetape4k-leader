# Kover 보장 정책

## 현황

`bluetape4k-leader`는 이미 선택한 모듈에 대해 Kover 검증 범위를 시행합니다.

| Module | Threshold | Rationale |
|---|---:|---|
| `leader-core` | 80% | Core public API and contract logic. |
| `leader-micrometer` | 80% | Metrics export behavior is unit/integration-testable. |
| `leader-zookeeper` | 80% | Backend contract has stable Testcontainers coverage. |
| `leader-spring-boot` | 80% | Production Spring Boot auto-configuration plus AspectJ CTW integration; generated Spring AOT/TestContext classes are excluded from reporting. |

## 정책

상태: 검증된 모듈에 대해 시행됩니다. 다른 곳에서는 통합이 많이 필요한 예외를 문서화했습니다.

Redis, MongoDB, Exposed, Hazelcast 또는 Ktor에 의존하는 백엔드 모듈은 각각 측정된 기준선과 현실적인 임계값이 있을 때까지 보고
전용 상태를 유지합니다.

## 임계값 계획

- Nightly에서 기존 경계를 계속 적용합니다.
- 안정적인 모듈 적용 범위가 측정된 후에만 백엔드별 임계값을 추가하세요.
- 순수 계약 모듈의 경우 80%를 선호하고 통합이 많은 백엔드의 경우 60-70%를 선호합니다.
- `main` 소스 세트에 대해서만 `leader-spring-boot`를 측정합니다. 생성된 Spring AOT/TestContext 빈 정의 및
- AspectJ 합성 클로저 클래스는 테스트 가능한 프로덕션 동작이 아니라 생성된 계측 아티팩트이므로 Kover 보고서에서 제외합니다.

## CI/야간 계약

Nightly는 시행 테이블에 나열된 모든 모듈에 대해 `koverVerify`를 실행해야 합니다.
