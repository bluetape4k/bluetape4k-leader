# Lessons — Issue 412 K8s group slot Nightly coverage

Date: 2026-05-29
Issue: #412
Branch: test/412-design-406-k8s-nightly-etcd-metadata

## Context

Issue #404 added Kubernetes Lease-per-slot group election and K3s tests. The follow-up #412 exists to make sure
the privileged group-slot runtime coverage remains in the Nightly full lane rather than only in local evidence.

## Decision

Keep pull request CI on the non-K3s `:bluetape4k-leader-k8s:test` slice. Keep real K3s runtime coverage in the
Nightly full `test-leader-k8s` job and make the job, Gradle task description, and README text explicitly mention
Lease-per-slot group slot coverage.

## Outcome

The Nightly full job still runs `:bluetape4k-leader-k8s:test :bluetape4k-leader-k8s:k8sTest`. The `k8sTest` task
includes the blocking and suspend group K3s test classes added by #404, so acquire, contention, release,
reacquire, expiry takeover, and cancellation/error cleanup paths are covered in the privileged lane.

## Verification

- `./gradlew :bluetape4k-leader-k8s:test --no-daemon` passed.
- `./gradlew :bluetape4k-leader-k8s:cleanK8sTest :bluetape4k-leader-k8s:k8sTest --no-daemon --max-workers=1 --no-build-cache` passed with 20 K3s tests.
- `actionlint .github/workflows/nightly-tests.yml` passed.
- `git diff --check` passed.

## Future Guard

When K3s-only tests are added to `leader-k8s`, keep them tagged `k8s` and verify that the Nightly full
`test-leader-k8s` job still runs `:bluetape4k-leader-k8s:k8sTest`. Do not move privileged K3s coverage into the
fast pull request CI lane.
