# 배운 교훈 - Issue 231 K8s 운전자 예

## 맥락

`leader-k8s`를 사용할 수 있지만 Spring Boot 모듈은 아직 Kubernetes Lease 팩토리를 자동 구성하지 않습니다. Spring 자동 구성 계약을 확장하지 않고 연산자 패턴을 표시하는 데 필요한 예제입니다.

## 결정

명시적인 애플리케이션 연결 사용: `KubernetesLeaseLeaderElector` Bean을 생성하고 `runIfLeader()`로 예약된 조정 루프를 보호합니다. 일반 테스트에서는 가짜 `LeaderElector`를 사용합니다. K3s 검증은 태그된 `k8sTest` 작업에 유지됩니다.

## 결과

백엔드 종속성이 표시되고 RBAC/배포 매니페스트가 리스 권한과 3-복제본 형태를 직접 선언하므로 이 예는 실제 연산자로 복사할 수 있습니다.

## 검증

- `./gradlew :examples:k8s-operator:test --no-configuration-cache --console=plain`
- `./gradlew projects :examples:k8s-operator:processAot :examples:k8s-operator:processTestAot --no-configuration-cache --console=plain`
- `./gradlew :examples:k8s-operator:k8sTest --no-configuration-cache --console=plain`
- `actionlint .github/workflows/ci.yml .github/workflows/examples.yml`

## 퓨쳐 가드

나중에 `leader-spring-boot`가 Kubernetes 백엔드 자동 구성을 얻는 경우 이 예를 업데이트하여 명시적인 Bean 배선과 속성 기반 자동 구성을 모두 표시하되 K3s 테스트는 일반 CI 외부로 유지하세요.
