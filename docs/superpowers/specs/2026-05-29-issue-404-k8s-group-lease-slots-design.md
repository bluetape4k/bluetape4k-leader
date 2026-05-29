# Issue 404 Kubernetes Lease-per-slot group election design

## Context

Issue #404 extends `leader-k8s` from single Kubernetes Lease leader election to bounded group election.
Issue #335 intentionally excluded group semantics until a safe Lease-per-slot model was designed.
Issue #411 now exposes elected lease metadata to observers, which makes group state snapshots more useful.

Existing `leader-k8s` single election already has the required safety primitives:

- a per-acquisition fencing token in `spec.holderIdentity`;
- audit and physical node identity in bluetape4k annotations;
- Kubernetes optimistic concurrency through `metadata.resourceVersion`;
- owner-conditional release and extension;
- K3s-backed integration tests.

## Goals

1. Add blocking and suspend Kubernetes group electors:
   - `KubernetesLeaseLeaderGroupElector : LeaderGroupElector`;
   - `KubernetesLeaseSuspendLeaderGroupElector : SuspendLeaderGroupElector`.
2. Represent each group slot as one Kubernetes Lease object.
3. Preserve owner-conditional acquire, release, and extension for every slot.
4. Report group state with active count and per-slot `LeaderLease` snapshots.
5. Document group naming, RBAC, cleanup, and operational behavior in English and Korean READMEs.
6. Cover acquire, contention, release/reacquire, expiry takeover, cancellation cleanup, and state mapping with K3s tests.

## Non-Goals

- Do not change `leader-core` group interfaces.
- Do not use Kubernetes `LeaseCandidate` or control-plane coordinated leader election APIs.
- Do not delete Lease objects on normal release. Group slots follow the single-Lease behavior: release clears holder identity or shortens the lease while preserving operator-visible Lease records.
- Do not add Spring AOP group stream support. Issue #410 owns those semantics.
- Do not add Nightly/CI workflow changes unless the new tests need a new job. Existing `leader-k8s` K3s coverage already runs in the affected module lane.

## Public API

Add `KubernetesLeaseLeaderGroupElector`:

- constructor parameters: caller-owned `KubernetesClient`, `KubernetesLeaseGroupOptions`, and optional `Clock`;
- implements blocking `runIfLeader(lockName)`, `runIfLeader(slot)`, result variants, inherited async bridge, and group state methods;
- extension functions:
  - `KubernetesClient.runIfLeaderGroup(...)`;
  - `KubernetesClient.runAsyncIfLeaderGroup(...)`.

Add `KubernetesLeaseSuspendLeaderGroupElector`:

- constructor parameters: caller-owned `KubernetesClient`, `KubernetesLeaseGroupOptions`, and optional `Clock`;
- wraps Fabric8 calls in `Dispatchers.IO`;
- releases in `NonCancellable + Dispatchers.IO` and rethrows coroutine cancellation;
- extension function:
  - `KubernetesClient.suspendRunIfLeaderGroup(...)`.

Add `KubernetesLeaseGroupOptions`:

- wraps `LeaderGroupElectionOptions`;
- carries the Kubernetes namespace and retry delay;
- validates `maxLeaders`, namespace, and retry delay;
- keeps single-election options unchanged for source compatibility.

## Lease Naming

Each logical group lock owns one Lease per slot:

```text
<lockName>-slot-<slotIndex>
```

where `slotIndex` is `0 until maxLeaders`.

Because Kubernetes Lease names are DNS-1123 labels with a 63-character limit, the group lock name must leave room
for the longest slot suffix. The implementation validates the derived Lease name before acquisition. If the user
passes a lock name that cannot produce valid slot Lease names, the elector fails fast with `IllegalArgumentException`.

## Acquisition Model

For each acquisition attempt:

1. Pick a random start slot to reduce slot-0 hotspots.
2. Visit each slot at most once.
3. Allocate the remaining `waitTime` across the remaining slots so one contended slot cannot consume the entire budget.
4. Create a fresh `KubernetesLeaseLock` for the derived slot Lease name and a fresh owner token.
5. Try to acquire that slot Lease using the existing single-Lease lock algorithm.
6. If acquired, run the action with a `LeaderLockHandle` whose identity kind is `GROUP`, `groupParams.maxLeaders` is set, and `slotId` is the acquired slot index.
7. If no slot is acquired before the deadline, return `null` / `LeaderRunResult.Skipped`.

This model intentionally reuses the single-Lease fencing token and resource-version compare-and-set behavior.

## Release and Extension

Release and auto-extension are per acquired slot:

- release calls `KubernetesLeaseLock.unlock(minLeaseTime, acquiredAtNanos)`;
- release is owner-conditional and does not affect other slots;
- lock-handle extension uses one `KubernetesLeaseLockExtendDelegate` bound to the acquired slot lock;
- automatic watchdog extension is not introduced for group electors in this PR because core
  `LeaderGroupElectionOptions` has no `autoExtend` contract. This PR fixes the existing watchdog close race for
  single-election paths, but keeps group auto-extension as a separate contract decision;
- suspend cleanup runs in `NonCancellable + Dispatchers.IO`.

## State Mapping

`state(lockName)` maps every slot Lease into `LeaderLease` snapshots:

- absent, blank-holder, and expired slot Leases are ignored;
- occupied slot Leases map through `KubernetesLeaseStateMapper`;
- each returned lease sets `slot = slotIndex`;
- `activeCount = leaders.size`;
- `availableSlots = maxLeaders - activeCount`.

State remains observability-only. Acquisition and release correctness depends only on per-slot owner-conditional Kubernetes updates.

## Cleanup

Normal release does not delete slot Lease resources.

Tests and operational cleanup may delete all derived slot Lease names. README guidance must state that RBAC needs
`get`, `create`, `update`, `patch`, and usually `delete` for test/cleanup tooling; production electors do not need
`delete` for normal action lifecycle.

## Failure and Cancellation Contracts

- Contention returns `null` / `Skipped`.
- Action exceptions propagate for `runIfLeader` and become `ActionFailed` for result APIs after election.
- Java `CancellationException`, Kotlin `CancellationException`, and `InterruptedException` keep existing propagation semantics.
- Kubernetes `409 Conflict` is contention and retry input, not a user-visible failure.
- Cleanup failures are logged and swallowed after the action result is determined.

## Verification Requirements

- Compile and targeted unit tests for `leader-k8s`.
- K3s tests for:
  - multiple concurrent acquisitions up to `maxLeaders`;
  - extra contender skipped when all slots are occupied;
  - release/reacquire;
  - expired slot takeover;
  - slot audit identity and state lease metadata;
  - suspend cancellation cleanup;
  - group slot cleanup helper.
- Sequential Testcontainers/K3s verification.
- Full compile/build lane excluding heavyweight tests before PR if K3s has already run.

## Step 2-R Design Review

| Tier | Scope | P0 | P1 | P2 | P3 | Notes |
|---|---|---:|---:|---:|---:|---|
| 1 Security | Lease names, annotations, RBAC | 0 | 0 | 0 | 0 | No secret data added; names validated as DNS-1123 labels. |
| 2 Ops/SRE | cleanup, diagnosis, K3s lifecycle | 0 | 0 | 1 | 0 | Keep pass-after-retry K3s flake visible in PR if it recurs. |
| 3 Structure | core API and module boundary | 0 | 0 | 0 | 0 | No `leader-core` API change needed. |
| 4 Kotlin/API | options, constructors, extension functions | 0 | 0 | 0 | 0 | Mirrors existing K8s and etcd group patterns. |
| 5 Tests/types | group state, cancellation, result APIs | 0 | 0 | 0 | 0 | Tests explicitly named in verification requirements. |
| 6 Performance/stability | slot scan, retry budget, watchdog | 0 | 0 | 0 | 0 | Random start slot avoids persistent slot-0 hotspot. |
| 7 Docs/release | README, lesson, CI evidence | 0 | 0 | 0 | 0 | Existing leader-k8s CI/Nightly lanes should cover changed module. |

Step 2-R status: PASS. P0 = 0, P1 = 0.
