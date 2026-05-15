# Issue 238 StateFlow Group Semantics

## Context

`leaderStateFlow()` is a single-leader projection: every `Revoked(lockName)` maps to `LeaderState.empty(lockName)`. Group electors with `maxLeaders > 1` can still have active slots after one slot is revoked, so using `leaderStateFlow()` for group state can report an empty lock too early.

## Decision

Keep `leaderStateFlow()` as the single-leader API and document that boundary. Add `leaderGroupStateFlow(lockName, maxLeaders, scope, started)` for group electors, projecting lifecycle events into `LeaderGroupState.activeCount`.

The group projection intentionally leaves `leaders` empty because `LeaderElectionEvent.Revoked` has no leader or slot identity. Count semantics are reliable for balanced elected/revoked events; identity semantics would require a future event contract change.

## Outcome

Group consumers can observe partial revokes without collapsing the whole group to empty. Existing single-leader behavior and the issue #237 eager hot-flow subscription fix remain unchanged.

## Verification

- `./gradlew :leader-core:test --tests 'io.bluetape4k.leader.coroutines.LeaderStateFlowExtTest' --no-configuration-cache --console=plain`
- Result: 12 tests passing, build successful.
- Added tests for partial revoke, max leader capping, skipped events, and lock-name filtering.
- Claude advisor review was attempted twice, but the local CLI produced no usable output and was terminated after prolonged silence. Artifact: `.omx/artifacts/claude-issue-238-stateflow-group-semantics-20260516014229.md`.

## Future Notes

If group event identity becomes necessary, extend `LeaderElectionEvent.Revoked` with leader or slot identity before adding identity-preserving group state. Do not infer remaining leaders from a count-only revoke event.
