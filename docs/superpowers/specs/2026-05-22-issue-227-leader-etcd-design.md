# Issue 227 leader-etcd Design

## Context

#227 adds a publishable `leader-etcd` backend for environments where etcd v3 is
already the coordination datastore. The backend must preserve the shared
`leader-core` contract while using etcd's server-side leases, strict
serializability, and gRPC concurrency APIs instead of local wall-clock deadline
state.

This is Type A Full Design work because it introduces a new backend module, a new
third-party client dependency, public APIs, integration tests, README pairs, BOM
constraints, CI/nightly workflow coverage, and a likely prerequisite testcontainers
fixture.

## Goals

1. Add `bluetape4k-leader-etcd` as a publishable module.
2. Implement blocking, async, coroutine, and virtual-thread single-leader APIs
   backed by etcd v3 lease-bound ownership.
3. Implement blocking and coroutine multi-leader group APIs with bounded slot
   ownership under one lock namespace.
4. Preserve shared `leader-core` behavior:
   - contention returns `null`;
   - action exceptions propagate;
   - coroutine cancellation rethrows `CancellationException`;
   - release and extend never affect another owner's lease/key;
   - state APIs report `LeaderState` / `LeaderGroupState` from current backend
     state, not from cached local assumptions.
5. Add real etcd integration coverage for acquire, contention, release/reacquire,
   lease expiry takeover, auto-extension, min-lease-time behavior, group slots,
   and cleanup.
6. Document dependency, client lifecycle ownership, key layout, options, usage,
   and test runner requirements in English and Korean READMEs.
7. Wire Gradle settings, version catalog, BOM constraints, CI, nightly tests, and
   coverage aggregation.

## Non-Goals

- Do not add Spring Boot auto-configuration in the first PR. `leader-spring-boot`
  backend selection can follow after the core module is proven.
- Do not make watch delivery part of acquire/release correctness. Watches may
  feed event publication, but correctness must rely on lease/key state.
- Do not introduce raw `GenericContainer` usage if a reusable
  `EtcdServer.Launcher` can be added to `bluetape4k-testcontainers`.
- Do not change `leader-core` interfaces for etcd-specific capabilities.
- Do not make the module close a caller-supplied `io.etcd.jetcd.Client`; caller
  owns client lifecycle.

## Evidence

- GitHub #227 requests a `leader-etcd` module with all leader interfaces,
  lease-based crash recovery, watch-driven event support, Testcontainers, CI,
  BOM, and README coverage.
- qmd first-pass retrieval found the prior etcd research note and the existing
  backend design style from `leader-k8s`.
- PullMD service research is saved under:
  - `~/work/bluetape4k/wiki/research/2026-05-22-issue-227-leader-etcd-pullmd.md`
  - `~/work/bluetape4k/wiki/research/2026-05-22-issue-227-etcd-official-docs-pullmd.md`
- qmd verification:
  `qmd search "jetcd LeaseGrant Lock service" -c bluetape4k-wiki --files --all`
  returns the official-docs PullMD artifact.
- etcd v3.6 official docs establish:
  - KV operations are atomic and strictly serializable by default.
  - Range reads are linearizable by default; serializable reads can be stale.
  - Transactions provide atomic If/Then/Else guards over key version, create
    revision, mod revision, or value.
  - Leases attach TTL-based liveness to keys; lease expiry or revoke deletes all
    keys attached to that lease.
  - The Lock service returns a unique ownership key that exists while held and
    releases on `Unlock` or lease expiry.
  - Watch events are ordered, unique, reliable within the history window, and
    resumable, but watch operations themselves are not linearizable.
- Maven Central metadata for `io.etcd:jetcd-core` reports latest/release
  `0.8.6` as of 2026-05-22.
- `jetcd-core:0.8.6` source jar confirms:
  - `Client.builder().endpoints(...).build()` constructs clients.
  - `Client` exposes `getLeaseClient()`, `getLockClient()`, `getKVClient()`,
    `getWatchClient()`, and `getElectionClient()`.
  - `Lease.grant(ttlSeconds)`, `Lease.keepAliveOnce(leaseId)`,
    `Lease.keepAlive(leaseId, observer)`, and `Lease.revoke(leaseId)` return
    `CompletableFuture` or closeable keepalive handles.
  - `Lock.lock(ByteSequence name, long leaseId)` and
    `Lock.unlock(ByteSequence lockKey)` are `CompletableFuture` APIs.
  - `LockResponse.getKey()` returns the opaque ownership key.
- `bluetape4k-projects` currently has `ConsulServer` but no `EtcdServer` in
  `testing/testcontainers`, so the preferred test fixture is a prerequisite
  addition to `bluetape4k-testcontainers`.

## Design

### Module

Add `leader-etcd` and register it as project `:bluetape4k-leader-etcd`.

Dependencies:

- `api(project(":bluetape4k-leader-core"))`
- `api(libs.jetcd.core)`
- `implementation(libs.kotlinx.coroutines.jdk8)` for
  `CompletableFuture.await()` from suspend code. The current catalog has
  coroutine core/reactive/reactor/test aliases but no jdk8 alias.
- `testImplementation(testFixtures(project(":bluetape4k-leader-core")))`
- `testImplementation(libs.bluetape4k.junit5)`
- `testImplementation(libs.bluetape4k.testcontainers)`
- `testImplementation(libs.kotlinx.coroutines.test)`
- Testcontainers dependencies matching the existing integration-test pattern.

Add to `gradle/libs.versions.toml`:

- `jetcd = "0.8.6"`
- `jetcd-core = { module = "io.etcd:jetcd-core", version.ref = "jetcd" }`
- `kotlinx-coroutines-jdk8 = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-jdk8" }`

The module is publishable and must be included in
`bluetape4k-leader-bom/build.gradle.kts`.

### Public API

Initial public types:

- `EtcdLeaderElectionOptions`
  - wraps `LeaderElectionOptions`;
  - `namespace` or `keyPrefix`;
  - `retryDelay`;
  - validates non-blank prefix and positive retry delay.
- `EtcdLeaderElector`
  - implements `LeaderElector`;
  - supports blocking and async execution.
- `EtcdVirtualThreadLeaderElector`
  - implements `VirtualThreadLeaderElector` using the repo's virtual thread
    executor pattern.
- `EtcdSuspendLeaderElector`
  - implements `SuspendLeaderElector`;
  - bridges jetcd `CompletableFuture` APIs through coroutine-aware await helpers,
    not production `runBlocking`.
- `EtcdLeaderGroupElector`
  - implements `LeaderGroupElector`.
- `EtcdSuspendLeaderGroupElector`
  - implements `SuspendLeaderGroupElector`.
- Factory types matching other backends:
  - `EtcdLeaderElectorFactory`
  - `EtcdSuspendLeaderElectorFactory`
  - `EtcdLeaderGroupElectorFactory`
  - `EtcdSuspendLeaderGroupElectorFactory`

Extension functions:

- `Client.runIfLeader(...)`
- `Client.runAsyncIfLeader(...)`
- `Client.suspendRunIfLeader(...)`
- `Client.runIfLeaderGroup(...)`
- `Client.suspendRunIfLeaderGroup(...)`

KDoc must state that the caller owns and closes the supplied jetcd `Client`.

### Key Layout

Default key prefix: `/bluetape4k/leader`.

Single-leader lock name mapping:

```text
{prefix}/single/{escapedLockName}
```

Group lock slot mapping:

```text
{prefix}/group/{escapedLockName}/slot-{zeroBasedSlot}
```

Lock names should be passed through the existing `LockNameValidator`, then
encoded to a stable path segment. Use UTF-8 percent-encoding for every byte
outside `[A-Za-z0-9._-]`; slash is always encoded and never appears literally in
the encoded segment. This gives a reversible one-to-one mapping and prevents
path traversal or prefix-collision ambiguity.

### Single-Leader Ownership Model

Use the etcd Lock service with an explicit lease:

1. Create an etcd lease with `ttlSeconds = ceil(options.leaseTime)`.
2. Call `Lock.lock(lockNameKey, leaseId)`.
3. The returned `LockResponse.key` is the opaque ownership key.
4. Keep the returned ownership key as `ByteSequence` inside the internal etcd
   lease handle for owner-safe `Unlock`, and set `LeaderLockHandle.Real.token`
   to a stable UTF-8 string form of that key because the shared handle contract
   exposes `token: String`.
5. Execute the action inside the appropriate AOP scope.
6. Release with `Lock.unlock(ownershipKey)` when normal release is allowed.
7. Revoke the lease after unlock when it is safe to clean up, because the lease
   should not hold any remaining backend ownership.

The Lock service is preferred over raw KV transactions for the first
implementation because it is the etcd-provided concurrency primitive and it
already defines the ownership key + lease semantics needed by `leader-core`.

Important contract decisions:

- `Lock.lock(...)` may wait server-side. Client-side `waitTime` must still be
  enforced with a monotonic timeout around the returned `CompletableFuture`.
- If client-side `waitTime` expires before `Lock.lock(...)` completes,
  `CompletableFuture.cancel(...)` is only a best-effort hint. Correctness must
  come from unconditional lease cleanup: revoke the lease immediately on timeout,
  and attach a completion handler that calls `Lock.unlock(ownershipKey)` plus a
  second `Lease.revoke(leaseId)` if the lock call later completes with ownership.
  A timed-out caller must never leave a phantom lock behind.
- Timeout or normal contention returns `null`; backend errors are logged and
  classified consistently with other backends.
- `minLeaseTime` delays unlock/revoke until the minimum hold window has elapsed.
  It must not extend another owner; it only keeps this acquisition's lease alive
  before release.
- `autoExtend=true` starts `LeaderLeaseAutoExtender`; its delegate renews the
  same lease through `Lease.keepAliveOnce(leaseId)`.
- `autoExtend=false` does not keep the lease alive beyond the initial TTL. If the
  action outlives `leaseTime`, leadership may be lost just like other TTL-based
  backends without auto-extension.

### Extend Delegate

`EtcdLockExtendDelegate` owns:

- the lease id;
- the returned ownership key;
- released flag;
- monotonic acquisition timestamp.

`extend(lockAtMostFor)` behavior:

1. Return `NotHeld` if already released.
2. Renew the lease using `Lease.keepAliveOnce(leaseId)`.
3. Verify the ownership key with a linearizable `KV.get` only when keepalive
   success/failure is ambiguous or when mapping current state.
4. Return `Extended(observedExpireAt)` when renewal succeeds.
5. Return `NotHeld` when the ownership key is missing, the lease no longer
   exists, or etcd reports the lease/key is gone.
6. Return `BackendError` for transient/unclassified backend failures.

The delegate must not create a new lease for an already lost lock, because that
would revive a stale owner. It must use the original `ByteSequence` ownership
key for unlock and ownership checks; the string token is only the shared
`leader-core` handle token.

Keep-alive cadence:

- `LeaderLeaseAutoExtender` should request extension at roughly
  `leaseTime / 3`, with ±10% jitter to avoid synchronized renewals across many
  JVMs.
- A failed keepalive gets one immediate retry before the delegate returns
  `NotHeld` or `BackendError` according to the observed failure.
- Extend should rely on `Lease.keepAliveOnce` success and positive TTL where
  possible. A linearizable `KV.get` for the ownership key is reserved for
  ambiguous failure/state paths to avoid adding a quorum read to every renewal.

### State Mapping

`EtcdLeaderStateMapper` reads the current key state:

- missing ownership key or no lock holder -> `LeaderState.empty(lockName)`;
- present ownership key -> `LeaderState.occupied(lockName, LeaderLease(...))`.

Because the etcd Lock service owns opaque lock internals, state mapping may need
to read the lock prefix and choose the lowest create revision as the current
owner. The implementation should prefer the API shape that compiles cleanly
against jetcd `KV` and `GetOption`:

- prefix range over the escaped lock path;
- linearizable read;
- sort by create revision ascending;
- first key = current owner.

`LeaderLease.auditLeaderId` should use `LeaderElectionOptions.nodeId` when the
implementation can attach sidecar metadata safely to the same lease. If the Lock
service does not support rich value metadata without race-prone side effects, use
the opaque ownership key as the audit id in v1 and document that limitation.
The Election service can be reconsidered in a follow-up if richer leader
proposals become more valuable than the Lock service's simpler mutex semantics.

### Group Ownership Model

Implement group election as a bounded set of independent etcd locks:

1. For `maxLeaders = N`, candidates choose a random start slot
   `s in [0, N)` and then try `slot-s`, `slot-(s+1 mod N)`, ... until every
   slot has been attempted or the monotonic wait budget expires.
2. Each slot uses the same lease + Lock service flow as single-leader ownership.
3. Slot attempts continue until one slot is acquired or the monotonic `waitTime`
   budget expires.
4. A successful handle stores `slotId` and `groupParams`.
5. Release and extension only affect the acquired slot's ownership key and lease.

This mirrors the slot-token model already used by Redis backends and avoids a
raw "count keys then put" race.

### Suspend and Async Semantics

jetcd APIs are already `CompletableFuture` based. The implementation should:

- keep internal primitives future-first where practical;
- use coroutine await helpers for suspend APIs;
- rethrow `CancellationException` before broad exception handling;
- avoid `runCatching {}` around suspend calls;
- close/revoke acquired resources in `finally` on cancellation;
- keep blocking APIs as thin wrappers over future APIs with explicit monotonic
  timeout control.

### Event Publication

The first PR must include watch-backed event publication because #227's
acceptance criteria call it out explicitly. It must remain best-effort and
independently tested; lock correctness must not depend on watch delivery.

Watch-backed event publication:

- watch the single/group prefix;
- translate `PUT`/`DELETE` to elected/revoked-like events only after state
  revalidation when ambiguity exists;
- handle cancellation and compaction by restarting from a fresh current state;
- document that watch delivery is eventually observed and not part of lock
  correctness.
- expose a closeable publisher/decorator so tests and applications can stop the
  watch stream without closing the caller-owned jetcd `Client`.
- own an internal `SupervisorJob + Dispatchers.IO` scope by default, with an
  overload that accepts a caller-provided `CoroutineScope` for applications that
  need structured lifecycle ownership.
- restart failed watches with exponential backoff from 200 ms to 5 s; after 10
  consecutive restart failures, close the publisher and log the terminal failure.

### Test Fixture

Preferred prerequisite:

- Add `EtcdServer.Launcher.etcd` to `bluetape4k-testcontainers` in
  `bluetape4k-projects`, using the existing singleton server pattern.
- Use a maintained etcd image and expose the client endpoint.
- Then depend on `bluetape4k-testcontainers` from `leader-etcd` tests.
- Merge and publish the `EtcdServer.Launcher` SNAPSHOT before merging the
  `leader-etcd` PR. Until that artifact is available to CI, the `leader-etcd` PR
  must remain draft or use an explicitly documented fallback.

Fallback only if the prerequisite cannot land in the same working window:

- Use `io.etcd:jetcd-test` for integration tests and record the deviation in
  the plan/review notes.

### Documentation

Add `leader-etcd/README.md` and `leader-etcd/README.ko.md`.

Update root `README.md` and `README.ko.md` backend tables and dependency snippets.

README content must include:

- Gradle dependency;
- `Client.builder().endpoints(...).build()` setup;
- caller-owned client lifecycle;
- single and group examples;
- lease/auto-extension behavior;
- key prefix configuration;
- Testcontainers runner notes;
- comparison with ZooKeeper session locks and Redis TTL locks.

## Acceptance Criteria

- `:bluetape4k-leader-etcd` appears in Gradle projects and BOM constraints.
- `EtcdServer.Launcher` is merged and available from the
  `bluetape4k-testcontainers` dependency before the `leader-etcd` PR is merged,
  unless the PR explicitly adopts and documents the `jetcd-test` fallback.
- Public API KDoc is English and documents lifecycle/lease behavior.
- All required leader interfaces are implemented.
- Contention returns `null`; action exceptions propagate.
- Lease expiry allows another client to acquire the same lock.
- Auto-extension keeps a long-running action's lease alive.
- Timed-out `Lock.lock(...)` attempts clean up the lease and any late ownership
  key.
- `minLeaseTime` holds leadership for at least the configured minimum before
  release.
- Group elector never runs more than `maxLeaders` concurrent actions for one
  lock name.
- Suspend cancellation releases or revokes held resources and rethrows
  `CancellationException`.
- Watch-backed event publication observes lock changes through etcd watch and
  can be stopped without closing the caller-owned `Client`.
- Watch restart behavior is bounded by backoff and a terminal failure policy.
- Integration tests pass against real etcd.
- README pairs, CI, nightly, and lesson artifacts are updated.

## Risks

- `jetcd-core` brings gRPC/Netty/Vert.x transitive dependencies; compile and
  dependency-resolution checks must run before deeper implementation.
- The etcd Lock service may not provide enough value metadata for rich
  `LeaderState`; if so, v1 state may expose opaque ownership and record a
  follow-up for metadata keys or raw KV transactions.
- `Lock.lock` server-side waiting must not outlive `LeaderElectionOptions.waitTime`;
  client-side timeout and lease cleanup are mandatory.
- Watch-backed event publishing can be delayed or interrupted by compaction; it
  must stay outside the correctness path.
- Missing `EtcdServer.Launcher` in `bluetape4k-testcontainers` is a prerequisite
  gap for consistent integration tests.
- Linearizable reads in state and ambiguous extend paths add quorum RTT; hot
  renewals should not do a read-before-keepalive unless correctness requires it.

## Step 2-R Review Notes

### Codex Spec Review

| Priority | Finding | Decision |
|---|---|---|
| P1 | Watch-backed events were phrased as optional even though #227 acceptance criteria require them. | Accepted. Event publication is now required but explicitly outside acquire/release correctness. |
| P1 | `Lock.lock(...)` timeout cleanup did not cover late future completion. | Accepted. Spec now requires immediate lease revoke plus late unlock/revoke completion handler. |

### Claude Code Opus Advisor

Artifact: `.omx/artifacts/claude-issue-227-spec-20260522033027.md`

| Priority | Finding | Decision |
|---|---|---|
| P0 | Auto-extend cadence, retry, and jitter were undefined. | Accepted. Added `leaseTime / 3`, ±10% jitter, and one immediate retry before failure classification. |
| P0 | Future cancellation cannot be the correctness mechanism for timed-out `Lock.lock(...)`. | Accepted. Cancellation is now best-effort only; correctness comes from revoke plus late unlock/revoke handler. |
| P1 | Group slot order could cause slot-0 herd behavior. | Accepted. Added random start slot with modular traversal. |
| P1 | Lock-name path encoding was unspecified. | Accepted. Added reversible UTF-8 percent-encoding rules. |
| P1 | Cross-repo `EtcdServer.Launcher` prerequisite lacked merge/publish gate. | Accepted. Added SNAPSHOT availability gate before merge. |
| P2 | Lock vs Election service trade-off should be recorded. | Accepted. Added Election follow-up note under state mapping. |
| P2 | TLS/auth client configuration ownership was implicit. | Accepted for plan/docs. README tasks will state caller-owned `Client` security configuration. |
| P2 | Extend path should avoid linearizable `KV.get` on every keepalive. | Accepted. Keepalive success is primary; KV read reserved for ambiguous paths. |
| P2 | Watch publisher scope ownership was unspecified. | Accepted. Added default internal scope plus caller-provided scope overload. |
| P2 | Watch restart policy was unbounded. | Accepted. Added exponential backoff and terminal failure policy. |
| P3 | Coroutine future bridge should name the dependency. | Accepted. Added `kotlinx-coroutines-jdk8` catalog/dependency note. |

Rerun artifact: `.omx/artifacts/claude-issue-227-spec-rerun-20260522033257.md`

Rerun result:

| Priority | Finding | Decision |
|---|---|---|
| P2 | Group slot traversal still needs per-slot wait-budget handling so one contended slot cannot consume the whole group wait time. | Accepted for plan. Implementation plan must use bounded per-slot attempts under one monotonic overall budget. |
| P2 | Late unlock after timeout-triggered lease revoke may produce expected backend errors. | Accepted for plan. Timeout cleanup must treat already-revoked/already-deleted ownership as debug-level expected cleanup. |
| P2 | Opaque audit id fallback should become a follow-up if v1 cannot store metadata safely. | Accepted for plan. Plan must create or record a follow-up when rich audit state is deferred. |

Convergence: `P0 = 0`, `P1 = 0`.

## Step 0/1 Checklist Completion Report

| Item | Status | Notes |
|------|--------|-------|
| Feature worktree used | Done | `.worktrees/feat-issue-227-leader-etcd-design` on `feat/issue-227-leader-etcd-design`. |
| Target repository confirmed | Done | `bluetape4k-leader`, GitHub issue #227. |
| qmd queried before filesystem search | Done | `bluetape4k-wiki` and `bluetape4k-docs` queried. |
| PullMD research stored in shared wiki path | Done | `~/work/bluetape4k/wiki/research/`. |
| Official docs / source evidence checked | Done | etcd v3.6 docs, jetcd README, Maven metadata, jetcd source jar. |
| User intent clear | Done | Continue existing #227 work. |
