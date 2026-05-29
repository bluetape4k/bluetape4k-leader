# Issue 406 etcd richer leader metadata design

Date: 2026-05-29
Issue: #406
Status: Design decision

## Context

`leader-etcd` currently uses the jetcd Lock service with etcd leases. The Lock service gives the backend a safe
ownership key that exists only while the lease-backed lock is held. That fits the shared `leader-core` correctness
contract: acquire waits behind contenders, unlock uses the opaque ownership key, and lease expiry or revoke releases
ownership.

The tradeoff is observability. The Lock service ownership key is not an application metadata record. Current
`LeaderState` / `LeaderGroupState` mapping can report the backend ownership token, and group state reads only the
lowest `createRevision` ownership key for each slot so queued contenders are not counted as leaders. It cannot
claim human-readable audit identity or node metadata without an additional metadata channel.

This design evaluates three options:

1. Keep Lock service only.
2. Keep Lock service for correctness and add sidecar metadata keys.
3. Migrate selected paths to the etcd Election service or raw KV transactions.

## Evidence

- etcd Lock API: a Lock request attaches ownership to a lease; if the lease expires or is revoked, the lock is
  released. The Lock response returns an ownership key, and etcd warns users not to modify that key because the
  lock may become undefined.
- etcd Election API: `Campaign` returns a `LeaderKey`; `Proclaim` updates the leader value; `Leader` returns the
  current proclamation; `Observe` streams ordered proclamations; `Resign` releases leadership.
- etcd KV metadata includes `create_revision`, `mod_revision`, `version`, `lease`, and `value`. etcd revisions are
  global logical clocks, and lock/election utilities rely on revision ordering for concurrency.
- Current code evidence:
  - `JetcdEtcdLockClient.lock()` wraps `client.lockClient.lock(lockKey, leaseId)` and stores the returned ownership
    key as the backend token.
  - `EtcdLockClient.ownershipKeys()` reads the lock prefix sorted by `CREATE ASC` with `limit(1)`, so state ignores
    queued contenders.
  - `EtcdLeaderElectionEventPublisher` watches the key prefix but revalidates current owner state before emitting
    events, because queued Lock contenders also create observable keys.
- Prior lesson: `docs/lessons/2026-05-22-issue-227-leader-etcd.md` explicitly records that richer sidecar metadata
  is needed before claiming human-readable group state from etcd.

## Decision

Keep the Lock service as the only correctness primitive for 0.3.0.

Do not migrate `leader-etcd` to the Election service or raw KV transactions in this milestone. The Lock service is
already implemented, tested with real EtcdServer coverage, wired into Spring, and aligned with the shared
`LeaderElector` / `LeaderGroupElector` contract. Replacing it would broaden the correctness surface across blocking,
async, suspend, virtual-thread, group, event-publisher, and Spring paths without changing the core leadership
contract.

If richer metadata becomes a runtime requirement, add sidecar metadata keys while keeping Lock service ownership as
the authority. Sidecar metadata must be observability-only. It must never decide acquire, release, extension, or
leader validity.

## Selected Future Shape: Lock + Sidecar Metadata

Sidecar metadata keys should be written only after Lock acquisition succeeds and should be attached to the same etcd
lease as the ownership key where jetcd APIs make that practical.

Suggested key layout:

```text
{keyPrefix}/metadata/single/{encodedLockName}/{ownershipTokenHash}
{keyPrefix}/metadata/group/{encodedLockName}/slot-{slot}/{ownershipTokenHash}
```

Suggested value shape:

```json
{
  "lockName": "daily-report",
  "kind": "SINGLE",
  "slot": null,
  "auditLeaderId": "worker-0",
  "nodeId": "worker-0",
  "ownershipToken": "<opaque-etcd-ownership-token>",
  "acquiredAtEpochMillis": 1780000000000,
  "leaseId": 12345,
  "schemaVersion": 1
}
```

The implementation should:

- write metadata after successful Lock acquisition;
- delete best-effort on explicit release, but rely on lease expiry/revoke for cleanup;
- read metadata only after confirming the current owner through `ownershipKeys()` / lowest `createRevision`;
- fall back to the opaque ownership token when metadata is missing, malformed, stale, or belongs to a non-current
  owner;
- never let delayed or compacted watch events define current leadership;
- keep the event publisher's current owner revalidation before emitting `Elected` / `Revoked`;
- avoid storing secrets or full payloads from user actions in metadata values.

## Rejected Alternatives

### Election service migration

Rejected for 0.3.0.

The Election service is attractive because it has explicit leader values and `Proclaim`, `Leader`, `Observe`, and
`Resign` operations. However, using it would require a new ownership model for every entry path, and group election
would still need one election namespace per slot. It would also change event semantics from Lock ownership key
changes to proclamation streams. That is too much churn for an observability improvement.

Use Election service later only if `leader-etcd` needs first-class etcd election proclamation semantics, not merely
human-readable `LeaderState`.

### Raw KV transaction ownership

Rejected for 0.3.0.

Raw KV transactions could encode ownership and metadata in one key and guard updates with version/create-revision
comparisons. That would maximize control, but it would duplicate the queueing, waiting, fairness, and session
behavior already provided by etcd's concurrency layer. It is only justified if Lock/Election APIs cannot satisfy a
future correctness requirement.

## Correctness Rules

- Lock ownership key remains the source of truth.
- Sidecar metadata must be treated as stale unless it matches the current owner.
- `LeaderState` and `LeaderGroupState` may improve observability but must remain snapshots, not acquisition gates.
- Watch/event delivery is not linearizable leadership proof; state revalidation remains required.
- No correctness path may depend on delayed, missing, or compacted watch events.
- Cleanup failure must degrade to lease expiry/revoke behavior, not leave an incorrect active state.

## Tests For A Future Implementation

If sidecar metadata is implemented later, add real EtcdServer-backed tests for:

- single leader state maps `auditLeaderId`, `nodeId`, and lease metadata when sidecar metadata exists;
- group state maps metadata only for the lowest-`createRevision` owner per slot;
- stale metadata for a queued or previous owner is ignored;
- malformed metadata falls back to the opaque ownership token;
- release deletes metadata when possible and lease revoke/expiry removes it otherwise;
- event publisher does not emit queued contender metadata as active leadership;
- compaction or watch restart does not make metadata a correctness source.

## Step 2-R Local 7-Tier Spec Review

| Tier | Result | P0 | P1 | P2 | P3 | Evidence |
| --- | --- | ---: | ---: | ---: | ---: | --- |
| 1 Security | PASS | 0 | 0 | 0 | 0 | Sidecar metadata explicitly excludes secrets and action payloads. |
| 2 Ops/SRE | PASS | 0 | 0 | 0 | 0 | Cleanup degrades to lease expiry/revoke; watch delivery is not correctness. |
| 3 Structural | PASS | 0 | 0 | 0 | 0 | Keeps existing Lock-service API and isolates future metadata as observability. |
| 4 Kotlin/API | PASS | 0 | 0 | 0 | 0 | No public API change selected for 0.3.0; future shape fits existing state APIs. |
| 5 Tests | PASS | 0 | 0 | 0 | 0 | Future tests enumerate stale, malformed, group, cleanup, and watch cases. |
| 6 Performance/Stability | PASS | 0 | 0 | 0 | 0 | Avoids raw KV/Election rewrite; future reads remain bounded by current owner checks. |
| 7 Docs/Release | PASS | 0 | 0 | 0 | 0 | Design records selected and rejected approaches with current code evidence. |

Step 2-R status: PASS. P0 = 0, P1 = 0.
