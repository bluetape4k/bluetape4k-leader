# Lessons — Issue 404 Kubernetes Lease-per-slot group election

Date: 2026-05-29
Issue: #404
Branch: feat/404-k8s-group-lease-slots

## Context

`leader-k8s` already had safe single-Lease election with a per-acquisition fencing token, but group election was
excluded until a Lease-per-slot model could preserve owner-conditional release and extension.

## Decision

Use one Kubernetes Lease per group slot named `<lockName>-slot-<slotIndex>`. Reuse `KubernetesLeaseLock` for each
slot so group election inherits the existing resource-version compare-and-set, holder fencing token, release, state
mapping, and extension delegate behavior.

Add `KubernetesLeaseGroupOptions` rather than changing `leader-core` group options. Do not add group `autoExtend`
in this PR because group auto-extension deserves a separate core-level contract. While running K3s verification,
fix the existing watchdog close path so release waits for an in-flight extension before backend cleanup.

## Outcome

The implementation adds blocking/async and suspend group electors, slot state mapping into `LeaderGroupState`, K3s
coverage for group acquisition and cleanup behavior, and README documentation in English and Korean.

## Verification

- `./gradlew :bluetape4k-leader-k8s:compileKotlin :bluetape4k-leader-k8s:compileTestKotlin :bluetape4k-leader-k8s:test --no-daemon` passed.
- First `./gradlew :bluetape4k-leader-k8s:k8sTest --no-daemon --max-workers=1` run passed the new group tests but exposed the existing suspend watchdog close race.
- `./gradlew :bluetape4k-leader-core:test --tests "io.bluetape4k.leader.LeaderLeaseAutoExtenderTest" --tests "io.bluetape4k.leader.LeaderLeaseAutoExtenderDelegateTest" --no-daemon` passed.
- `./gradlew :bluetape4k-leader-k8s:k8sTest --no-daemon --max-workers=1` passed after the watchdog close fix.
- `./gradlew build -x test -x k8sTest --no-daemon` passed.
- `git diff --check` passed.

## Future Guard

For Kubernetes group election, keep correctness on per-slot Lease ownership checks. `LeaderGroupState` is only an
observability snapshot and must not become an acquisition gate.
