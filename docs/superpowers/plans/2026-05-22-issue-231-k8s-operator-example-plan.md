# Issue 231 K8s Operator Example Plan

## 한국어 해설

이 문서는 `Issue 231 K8s Operator Example Plan`에 대한 설계 또는 실행 계획 기록입니다. 아래 원문 구조의 범위, 결정, 작업 순서, 검증 조건, 위험 및 후속 조치는 기록 보존을 위해 유지합니다. 검토자는 각 `Action`, `Expected DoD`, `Validation`, `Target files` 항목을 한국어 해설과 함께 읽고, 코드 식별자와 명령은 원문 그대로 취급해야 합니다.



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
