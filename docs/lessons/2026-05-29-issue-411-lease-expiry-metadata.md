# Lessons — Issue 411 lease expiry metadata

Date: 2026-05-29
Issue: #411
Branch: feat/411-lease-expiry-metadata

## Context

`LeaderLease` and several backend state snapshots already exposed `leaseUntil`, but listener callbacks and
`LeaderElectionEvent.Elected` did not carry a coherent lease snapshot.

## Decision

Keep the existing `onElected(lockName)` callback for source compatibility and add `onElected(lockName, leader)`.
Use `LeaderElectionEvent.Elected.fromLease()` to copy `LeaderLease.auditLeaderId` and `LeaderLease.leaseUntil`
into the existing `leaderId` and `leaseExpiry` fields while preserving the full optional lease snapshot.

## Outcome

Local blocking and suspend electors now pass best-effort `LeaderLease` metadata to listeners and lifecycle events.
Decorator electors read the delegate state immediately after acquisition and emit metadata when the delegate can
report it. `leaderStateFlow()` uses the full event lease snapshot when present and falls back to legacy
`leaderId`/`leaseExpiry` fields for older event shapes.

## Verification

- 7-Tier review found that group listener decorators could misreport aggregate `state().leaders.firstOrNull()` as
  current-slot metadata. Fixed by emitting `null` for group decorators unless the exact acquired lease is known.
- `./gradlew :bluetape4k-leader-core:test --tests "io.bluetape4k.leader.LeaderElectionEventTest" --tests "io.bluetape4k.leader.LeaderElectionListenerTest" --tests "io.bluetape4k.leader.coroutines.LeaderStateFlowExtTest" --no-daemon` passed: 49 tests.
- `git diff --check` passed.
- `./gradlew build -x test -x k8sTest --no-daemon` passed.
- `./gradlew build -x test --no-daemon` failed only in `:bluetape4k-leader-k8s:k8sTest` and
  `:examples:k8s-operator:k8sTest` after the K3s API endpoint refused connections on `localhost:34491`.
- Retried the K3s lane sequentially with
  `./gradlew :bluetape4k-leader-k8s:k8sTest :examples:k8s-operator:k8sTest --no-daemon --max-workers=1`;
  it passed with 13 `leader-k8s` tests and 2 `examples:k8s-operator` tests.
- After the 7-Tier fix, a fresh sequential K3s lane rerun failed once in
  `KubernetesLeaseSuspendLeaderElectorK3sTest.watchdog auto extends lease during long suspend action` with
  `Expected <null> to equal to "reacquired"`. The same single test passed on immediate retry, so record this as
  a watchdog/K3s lifecycle flake to watch before PR merge.

## Future Guard

When core event metadata changes, update both the callback API and Flow projection tests. Treat lease expiry as
observability metadata only; ownership decisions must remain on backend atomic acquire paths.
