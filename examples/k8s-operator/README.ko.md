# Kubernetes Operator Leader Election 예제

[English](README.md) | [한국어](README.ko.md)

이 예제는 Kubernetes operator에서 자주 쓰는 single-active-controller 패턴을
보여줍니다. 같은 Spring Boot 애플리케이션을 3개 pod로 실행해도 Kubernetes
`coordination.k8s.io/v1` Lease를 보유한 pod 하나만 mock custom resource
reconcile loop를 실행합니다.

## 시나리오

3개 replica가 같은 Spring Boot operator controller를 실행합니다. 모든 replica는
scheduled tick을 유지하지만, `KubernetesLeaseLeaderElector.runIfLeader`가
`cronjob-reconciler` Lease를 가진 pod 하나만 reconcile workload를 호출하게 합니다.
리더가 Lease 갱신을 멈추면 이후 tick에서 다른 pod가 인계할 수 있습니다.

## 아키텍처 다이어그램

![k8s operator Architecture diagram](../../docs/images/readme-diagrams/examples-k8s-operator-architecture-01.png)

## 시퀀스 다이어그램

![k8s operator Sequence Flow diagram](../../docs/images/readme-diagrams/examples-k8s-operator-sequence-01.png)

## 보여주는 것

- `leader-k8s`를 operator election backend로 사용하는 방법
- `KubernetesLeaseLeaderElector.runIfLeader`로 보호한 scheduled reconcile loop
- tick은 유지하지만 reconcile workload는 건너뛰는 standby pod
- 3-replica operator용 RBAC/Deployment manifest
- contention과 failover를 검증하는 K3s 통합 테스트

## 로컬 실행

일반 단위 테스트는 Docker가 필요 없습니다.

```bash
./gradlew :examples:k8s-operator:test
```

K3s 기반 테스트는 Docker privileged mode가 필요합니다.

```bash
./gradlew :examples:k8s-operator:k8sTest
```

## Operator 구조

```kotlin
@Scheduled(fixedDelayString = "\${demo.operator.fixed-delay-ms:5000}")
fun reconcileTick() {
    leaderElector.runIfLeader("cronjob-reconciler") {
        workload.reconcile(request)
    }
}
```

현재 leader pod가 종료되거나 Lease 갱신을 멈추면 다른 pod가 다음 tick에서
같은 lock을 획득하고 reconcile을 이어갈 수 있습니다.

## Kubernetes Manifest

이 모듈에서 빌드한 이미지로 `deployment.yaml`의 image 값을 교체한 뒤 적용합니다.

```bash
kubectl apply -f k8s/rbac.yaml
kubectl apply -f k8s/deployment.yaml
kubectl logs deploy/bluetape4k-k8s-operator -f
```

ServiceAccount에는 대상 namespace의 `coordination.k8s.io/leases`에 대해
`get`, `create`, `update`, `patch`, `delete` 권한이 필요합니다.
