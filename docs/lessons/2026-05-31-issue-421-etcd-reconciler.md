# Issue #421 etcd Reconciler Example

## Context

`bluetape4k-leader-etcd` had backend coverage but no runnable etcd example in
the examples catalog.

## Decision

Added `examples/etcd-reconciler` as a framework-neutral control-plane scenario.
The example uses `EtcdLeaderElector` directly instead of introducing a Spring
Boot or Ktor adapter.

## Outcome

The example demonstrates active leader work, skip-on-contention behavior, and
reacquire after release against a real etcd Testcontainers instance.

## Verification

- `./gradlew :examples:etcd-reconciler:test`

## Future Guidance

When adding example modules, update settings, root README locale set, CI path
filters/jobs, and the weekly Examples workflow in the same change.
