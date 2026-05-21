# Issue 231 K8s Operator Example Plan

- Issue: #231
- Date: 2026-05-22

## Tasks

1. Add `examples/k8s-operator` with Spring Boot application wiring.
2. Implement an operator controller that calls `LeaderElector.runIfLeader()`.
3. Add RBAC and 3-replica Deployment manifests.
4. Add unit tests for leader and standby behavior.
5. Add K3s tagged integration tests for Kubernetes Lease contention/failover.
6. Register the module in `settings.gradle.kts`, README files, CI, and Examples
   workflow.
7. Verify with targeted tests, workflow lint, and module registration checks.

## Risk Controls

- Do not add Spring Boot Kubernetes backend auto-configuration in this example
  issue.
- Keep privileged K3s validation out of the normal CI path.
- Keep all public GitHub/README-facing text in English or synchronized localized
  docs.
