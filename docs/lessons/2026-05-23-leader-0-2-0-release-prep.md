# Lesson: 0.2.0 release prep dependency alignment

**Date**: 2026-05-23

## Context

The `0.2.0` milestone is complete and `develop` contains both the former
`0.1.1` maintenance scope and the newer `0.2.0` feature scope. Release prep
needed to convert the repo from snapshot metadata to final release metadata.

## Decision

Prepare `0.2.0` as the next official release and align repo-local
`bluetape4k-*` references before opening the release PR. In this cycle,
`bluetape4k-exposed` moved from `1.8.0` to the already published `1.9.0`
release train.

## Outcome

- `gradle.properties` now resolves the project version to `0.2.0`.
- Public dependency snippets now use `0.2.0`.
- `CHANGELOG.md` has a dated `0.2.0` section.
- `WIP.md` reflects the single remaining assigned issue for `0.2.1`.
- Workspace release procedure docs now record that upstream release prep must
  update repo-local `bluetape4k-*` dependency aliases.

## Verification

- `./gradlew properties --no-daemon | rg '^version:|^group:'` reported
  `group: io.github.bluetape4k.leader` and `version: 0.2.0`.
- `./gradlew build --no-daemon` compiled and executed downstream modules until
  `:bluetape4k-leader-k8s:k8sTest` failed because the local K3s Testcontainers
  endpoint was already refusing connections during Lease cleanup.
- Retrying `./gradlew :bluetape4k-leader-k8s:k8sTest --no-daemon` reached the
  K3s test body but left one watchdog reacquire assertion failing while the
  other 12 K3s tests passed.
- `./gradlew build -x :bluetape4k-leader-k8s:k8sTest --no-daemon` completed
  successfully, covering the rest of the release prep surface.

## Future Guidance

- Release prep PRs must check `gradle/libs.versions.toml` for stale
  `bluetape4k-*` aliases, not only the repo's own `baseVersion`.
- Treat local K3s endpoint refusal as an environment-sensitive validation gap;
  CI/Nightly should be the release gate for Kubernetes-backed tests.
