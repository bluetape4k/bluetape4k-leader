# Issue 231 K8s Operator Example Design

## 한국어 해설

이 문서는 `Issue 231 K8s Operator Example Design`에 대한 설계 또는 실행 계획 기록입니다. 아래 원문 구조의 범위, 결정, 작업 순서, 검증 조건, 위험 및 후속 조치는 기록 보존을 위해 유지합니다. 검토자는 각 `Action`, `Expected DoD`, `Validation`, `Target files` 항목을 한국어 해설과 함께 읽고, 코드 식별자와 명령은 원문 그대로 취급해야 합니다.



- Issue: #231
- Date: 2026-05-22
- Type: example module

## Goal

Add a runnable `examples/k8s-operator` module that demonstrates the
single-active-controller pattern on top of `leader-k8s`.

## Constraints

- `leader-k8s` has a backend API, but `leader-spring-boot` does not yet provide
  Kubernetes backend auto-configuration.
- Normal CI must avoid privileged Docker/K3s requirements.
- Example modules are non-published artifacts.

## Decision

Use an explicit `KubernetesLeaseLeaderElector` bean and guard the scheduled
operator reconcile loop with `runIfLeader()`. Keep K3s validation under a
separate `k8sTest` task and wire that task into the weekly Examples workflow.

## Acceptance

- `examples:k8s-operator:test` proves leader/standby controller behavior without
  Docker.
- `examples:k8s-operator:k8sTest` proves contention/failover against K3s.
- Root README and localized README list the new example.
- CI detects and runs the normal test lane; weekly Examples runs the K3s lane.
