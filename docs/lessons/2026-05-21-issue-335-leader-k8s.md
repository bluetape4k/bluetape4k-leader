# Issue 335 leader-k8s lesson

## Context

Issue #335 added a publishable Kubernetes Lease backend for `bluetape4k-leader`.
The backend had to support blocking, async, and suspend single-leader APIs while
using the native `coordination.k8s.io/v1` Lease object.

## Decision

Do not store `LeaderElectionOptions.nodeId` directly in `spec.holderIdentity`.
Use a per-acquisition fencing token as `holderIdentity`, and store display/audit
identity in bluetape4k annotations. This avoids duplicate execution when two
electors run in the same JVM or Pod with the same `nodeId`.

PR CI should run `:bluetape4k-leader-k8s:test` only. `koverXmlReport` currently
pulls custom `Test` tasks into its graph, so K3s coverage generation belongs in
Nightly full after `:k8sTest`, not in the fast PR lane.

## Outcome

Added `leader-k8s` with Fabric8 Kubernetes Client, owner-conditional create,
update, release, state mapping, README/RBAC guidance, SVG+PNG README diagrams,
BOM/settings wiring, and CI/Nightly jobs.

## Verification

- `./gradlew :bluetape4k-leader-k8s:compileKotlin :bluetape4k-leader-k8s:compileTestKotlin --no-daemon --console=plain`
- `./gradlew :bluetape4k-leader-k8s:test --no-daemon --console=plain`
- `./gradlew :bluetape4k-leader-k8s:k8sTest --no-daemon --console=plain`
- `./gradlew :bluetape4k-leader-k8s:koverXmlReport --no-daemon --console=plain`
- `actionlint .github/workflows/ci.yml .github/workflows/nightly-tests.yml`
- `xmllint --noout docs/images/readme-diagrams/leader-k8s-architecture-01.svg docs/images/readme-diagrams/leader-k8s-sequence-02.svg`
- `node /Users/debop/work/bluetape4k/.omx/scripts/audit-readme-diagrams.mjs .`
- `node /Users/debop/work/bluetape4k/.omx/scripts/audit-readme-diagram-quality.mjs .`

The module-level `detekt` task is not present for `:bluetape4k-leader-k8s`; use
the repository-level `detekt` lane when static analysis is required.

## Future Notes

Keep K3s tests tagged with `@Tag("k8s")` and excluded from the default `test`
task. If Kover is later configured to exclude custom integration test tasks,
coverage upload can return to the PR CI job.
