# Issue #421 etcd Reconciler Example Design

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
