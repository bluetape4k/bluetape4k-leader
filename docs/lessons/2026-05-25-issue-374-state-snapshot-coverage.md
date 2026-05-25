# Issue 374 State Snapshot Coverage

Context: Issue #374 required preview backend regression coverage for state
snapshots across Consul, DynamoDB, and etcd before the 0.2.2 release gate.

Decision: Add backend-specific integration tests instead of a shared contract
fixture because snapshot fidelity differs by backend. Consul and DynamoDB
single/group snapshots expose audit identity, physical node id, and lease
windows. etcd group snapshots expose the backend ownership token and slot, while
etcd single-leader `state()` remains the default empty snapshot because owner
metadata is not exposed by the current implementation.

Outcome: New tests cover empty state, occupied state, identity fields where the
backend can provide them, slot state for groups, and a holder/contender check
that verifies state reads do not release ownership or allow another candidate to
acquire prematurely.

Verification: `git diff --check`;
`./gradlew :bluetape4k-leader-consul:test :bluetape4k-leader-dynamodb:test :bluetape4k-leader-etcd:test --rerun-tasks --no-daemon`
with 24 DynamoDB, 54 Consul, and 63 etcd tests passing; focused reruns for
DynamoDB and etcd after final review cleanup. Claude artifacts:
`.omx/artifacts/claude-issue-374-state-snapshot-20260525120713.md` and
`.omx/artifacts/claude-issue-374-state-snapshot-final-20260525121050.md`, both
with P0=0 and P1=0.

Future guard: Do not assume state snapshot parity across backends. Tests should
name and assert backend-specific limitations explicitly instead of hiding them
behind non-null checks.
