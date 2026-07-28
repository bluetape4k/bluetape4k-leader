# Issue #421 etcd Reconciler Example Design

## 한국어 해설

이 문서는 `Issue #421 etcd Reconciler Example Design`에 대한 설계 또는 실행 계획 기록입니다. 아래 원문 구조의 범위, 결정, 작업 순서, 검증 조건, 위험 및 후속 조치는 기록 보존을 위해 유지합니다. 검토자는 각 `Action`, `Expected DoD`, `Validation`, `Target files` 항목을 한국어 해설과 함께 읽고, 코드 식별자와 명령은 원문 그대로 취급해야 합니다.



## Context

Issue #421 requests a runnable etcd-backed adoption example for
`bluetape4k-leader-etcd`. The existing etcd backend already provides blocking,
async, suspend, group, virtual-thread, and event-publisher APIs, but the examples
catalog had no etcd control-plane scenario.

## Goals

- Add `examples/etcd-reconciler`.
- Demonstrate one active control-plane reconciler for a shared lock.
- Show contention as skip-on-contention, not an exception path.
- Verify release and reacquire behavior with real etcd Testcontainers.
- Wire the module into settings, root README locale set, CI, and Examples
  workflow.

## Non-Goals

- No new `leader-etcd` library API.
- No Spring Boot or Ktor integration in this example.
- No production etcd TLS/auth bootstrap; the jetcd `Client` remains
  caller-owned.

## Design

`ControlPlaneReconciler` wraps `EtcdLeaderElector` and returns a serializable
`ReconcileReport`:

- elected nodes return `ReconcileStatus.APPLIED` with applied resource names;
- contending nodes return `ReconcileStatus.SKIPPED` with an empty resource list;
- `autoExtend=true` keeps long reconcile cycles alive while the body runs.

The runnable `EtcdReconcilerDemo` starts the shared `EtcdServer.Launcher.etcd`
container for local demonstration. Production users should create a jetcd
`Client` from their own endpoint, TLS, and authentication configuration.

## Acceptance Criteria

- `./gradlew :examples:etcd-reconciler:test` passes.
- Root `README.md` and `README.ko.md` list the example.
- `settings.gradle.kts` includes `examples:etcd-reconciler`.
- CI path filters and test jobs include the module.
- `.github/workflows/examples.yml` includes the module.
