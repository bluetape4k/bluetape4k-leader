# Lessons Learned — Issue 231 K8s Operator Example

## Context

`leader-k8s` is available, but the Spring Boot module does not yet auto-configure
Kubernetes Lease factories. The example needed to show an operator pattern
without expanding the Spring auto-config contract.

## Decision

Use explicit application wiring: create a `KubernetesLeaseLeaderElector` bean and
guard the scheduled reconcile loop with `runIfLeader()`. Normal tests use a fake
`LeaderElector`; K3s validation stays in a tagged `k8sTest` task.

## Outcome

The example is copyable into a real operator because the backend dependency is
visible and the RBAC/Deployment manifest declares the Lease permissions and
3-replica shape directly.

## Verification

- `./gradlew :examples:k8s-operator:test --no-configuration-cache --console=plain`
- `./gradlew projects :examples:k8s-operator:processAot :examples:k8s-operator:processTestAot --no-configuration-cache --console=plain`
- `./gradlew :examples:k8s-operator:k8sTest --no-configuration-cache --console=plain`
- `actionlint .github/workflows/ci.yml .github/workflows/examples.yml`

## Future Guard

If `leader-spring-boot` later gains Kubernetes backend auto-configuration, update
this example to show both explicit bean wiring and property-driven auto-config,
but keep the K3s test outside normal CI.
