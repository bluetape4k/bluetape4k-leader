# Issue #421 etcd Reconciler Example Plan

## Steps

1. Create `examples/etcd-reconciler` as an application module depending on
   `bluetape4k-leader-etcd`.
2. Add `ControlPlaneReconciler` and `EtcdReconcilerDemo`.
3. Add a Testcontainers-backed test for active leader, skipped contender, and
   reacquire after release.
4. Add English/Korean README files for the example.
5. Register the module in `settings.gradle.kts`, root README locale set, CI, and
   Examples workflow.
6. Validate with the targeted example test and workflow lint.

## Verification

- `./gradlew :examples:etcd-reconciler:test`
- `actionlint .github/workflows/ci.yml .github/workflows/examples.yml`
