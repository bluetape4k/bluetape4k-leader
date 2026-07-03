# Kubernetes Operator Leader Election Example

English | [한국어](README.ko.md)

This example shows the single-active-controller pattern used by Kubernetes
operators. Three pods can run the same Spring Boot application, but only the pod
that owns the Kubernetes `coordination.k8s.io/v1` Lease runs the mock custom
resource reconcile loop.

## Scenario

Three replicas run the same Spring Boot operator controller. Every replica keeps
its scheduled tick, but `KubernetesLeaseLeaderElector.runIfLeader` lets only the
pod that owns the `cronjob-reconciler` Lease call the reconcile workload. When
the leader stops renewing the Lease, another pod can take over on a later tick.

## Example Scenario

![K8s Operator scenario diagram](../../docs/images/readme-diagrams/examples-k8s-operator-scenario-01.png)

## Architecture Diagram

![k8s operator Architecture diagram](../../docs/images/readme-diagrams/examples-k8s-operator-architecture-01.png)

## Flow Diagram

![K8s operator flow diagram](../../docs/images/readme-diagrams/examples-k8s-operator-flow-01.png)

## Sequence Diagram

![k8s operator Sequence Flow diagram](../../docs/images/readme-diagrams/examples-k8s-operator-sequence-01.png)

## What It Shows

- `leader-k8s` as the election backend for an operator-style controller.
- A scheduled reconcile loop guarded by `KubernetesLeaseLeaderElector.runIfLeader`.
- Standby pods that keep ticking but skip the reconcile workload.
- RBAC and Deployment manifests for a 3-replica operator.
- A K3s integration test that proves contention and failover behavior.

## Run Locally

The normal unit test does not require Docker:

```bash
./gradlew :examples:k8s-operator:test
```

The K3s-backed test requires Docker privileged mode:

```bash
./gradlew :examples:k8s-operator:k8sTest
```

## Operator Shape

```kotlin
@Scheduled(fixedDelayString = "\${demo.operator.fixed-delay-ms:5000}")
fun reconcileTick() {
    leaderElector.runIfLeader("cronjob-reconciler") {
        workload.reconcile(request)
    }
}
```

When the current leader pod exits or stops renewing the Lease, another pod can
acquire the same lock on the next tick and continue reconciling.

## Kubernetes Manifests

Apply the manifests after replacing the image with one built from this module:

```bash
kubectl apply -f k8s/rbac.yaml
kubectl apply -f k8s/deployment.yaml
kubectl logs deploy/bluetape4k-k8s-operator -f
```

The runtime service account needs only `get`, `create`, `update`, and `patch`
on `coordination.k8s.io/leases` in the target namespace. Lease deletion is an
admin/test cleanup concern and is intentionally not granted to the running
operator.

The example Deployment uses a stable `0.5.0` image tag instead of `latest`.
Replace it with the immutable tag or digest you build for your own registry.

The probe contract is:

- `startupProbe` waits for the Spring Boot actuator endpoint before Kubernetes
  applies liveness decisions.
- `livenessProbe` restarts a pod whose actuator health endpoint stops
  responding.
- `readinessProbe` keeps non-ready pods out of service routing while the
  operator starts or recovers.
