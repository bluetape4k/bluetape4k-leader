# Issue 335 leader-k8s Design

## Context

#335 adds a publishable `leader-k8s` backend for Kubernetes controller and
operator deployments. The previous #248 work already proved a K3s-backed Fabric8
Lease example, but it intentionally stayed under `examples/k8s-lease` and did
not expose a reusable `leader-core` implementation.

This work promotes that proven Lease path into a publishable module while keeping
the Kubernetes operator example (#231) out of scope until the module is
releasable.

## Goals

1. Add `bluetape4k-leader-k8s` as a publishable module.
2. Implement blocking, async, and coroutine single-leader APIs backed by
   Kubernetes `coordination.k8s.io/v1` `Lease` objects.
3. Preserve the shared `leader-core` contract:
   - contention returns `null`;
   - action exceptions propagate;
   - coroutine cancellation rethrows `CancellationException`;
   - release and extend are owner-conditional.
4. Map Lease snapshots into `LeaderState` with holder identity, acquire time,
   renew time, and expiry data.
5. Add K3s integration coverage for acquire, contention, release/reacquire,
   expiry takeover, state mapping, and cleanup.
6. Document Gradle dependency, RBAC, options, usage, and K3s test runner
   requirements in English and Korean READMEs.
7. Wire Gradle settings, BOM constraints, CI, and scheduled/manual workflow
   coverage.

## Non-Goals

- Do not implement #231 `examples/k8s-operator`.
- Do not implement multi-leader Lease-per-slot semantics in the first PR.
- Do not depend on Kubernetes `LeaseCandidate` or coordinated leader election
  APIs. Those are Kubernetes control-plane beta/alpha features and are not
  needed for the module's first single-leader backend.
- Do not delete Lease objects on normal release. Release clears or shortens the
  holder conditionally, preserving a visible Lease record for operators.

## Evidence

- GitHub #335 requires a publishable `leader-k8s` module using
  `coordination.k8s.io/v1` Lease, plus K3s tests, README/RBAC docs, BOM, CI, and
  nightly wiring.
- `qmd query "leader-k8s Kubernetes Lease leader election bluetape4k #335 #231 #248"
  -c bluetape4k-docs --no-rerank` found #248 spec/plan/lesson and confirmed the
  earlier K3s Lease example is the relevant predecessor.
- `/Users/debop/.local/share/chezmoi/private_dot_codex/memories/MEMORY.md`
  records the local PullMD preference: after finding a URL, use
  `http://127.0.0.1:3333/api?url=...` to extract Markdown.
- PullMD extraction of Kubernetes "Leases" official docs says Lease objects are
  in the `coordination.k8s.io` API group, are used for node heartbeats and
  component-level leader election, and workloads may define their own Lease names.
- PullMD extraction of Kubernetes "Coordinated Leader Election" docs describes
  the Lease fields `holderIdentity`, `acquireTime`, `renewTime`,
  `leaseDurationSeconds`, and `leaseTransitions`, and states that clients rely on
  optimistic concurrency through `resourceVersion` so only one concurrent update
  succeeds.
- PullMD extraction of the Kubernetes Lease API reference confirms:
  - `acquireTime`: time the current lease was acquired.
  - `holderIdentity`: identity of the current holder.
  - `leaseDurationSeconds`: duration candidates wait to force acquire, measured
    against the last observed `renewTime`.
  - `leaseTransitions`: number of holder transitions.
  - `renewTime`: time the current holder last updated the lease.
- Fabric8 7.6.1 source jars in the local Gradle cache confirm:
  - `KubernetesClient.leases()` returns
    `MixedOperation<Lease, LeaseList, Resource<Lease>>`.
  - `LeaseSpec` uses `ZonedDateTime` for `acquireTime` and `renewTime`.
  - `LeaseSpecBuilder` supports copying and rebuilding typed Lease specs.
- `examples/k8s-lease` already compiles against Fabric8 typed Lease APIs and
  `K3sServer.Launcher.k3s`.
- Existing publishable backend modules place one module under `leader-*`, register
  `settings.gradle.kts`, add a BOM constraint, and wire `ci.yml` plus
  `nightly-tests.yml`.

## Design

### Module

Add `leader-k8s` and register it as project
`:bluetape4k-leader-k8s`.

Dependencies:

- `api(project(":bluetape4k-leader-core"))`
- `api(libs.fabric8.kubernetes.client)`
- `testImplementation(testFixtures(project(":bluetape4k-leader-core")))`
- `testImplementation(libs.bluetape4k.junit5)`
- `testImplementation(libs.bluetape4k.testcontainers)`
- `testImplementation(libs.kotlinx.coroutines.test)`
- Testcontainers dependencies matching the existing K3s example.

The module is publishable and therefore included in the BOM and NMCP/Kover root
aggregation automatically once it is not excluded by `isNonPublishedProject()`.

### Public API

Initial public types:

- `KubernetesLeaseOptions`
  - wraps `LeaderElectionOptions`;
  - `namespace`;
  - `retryDelay`;
  - `clock`;
  - validates namespace and retry delay.
- `KubernetesLeaseLeaderElector`
  - implements `LeaderElector`;
  - supports blocking and async execution.
- `KubernetesLeaseSuspendLeaderElector`
  - implements `SuspendLeaderElector`;
  - wraps blocking Fabric8 calls in `Dispatchers.IO`.
- `KubernetesLeaseStateMapper`
  - maps absent, empty, expired, and occupied Lease snapshots into
    `LeaderState`.
- Internal `KubernetesLeaseLock`
  - owns Lease acquire, release, extend, state, and delete-for-tests operations.
- Internal `KubernetesLeaseLockExtendDelegate`
  - adapts owner-conditional Lease renewal to `ExtendDelegate`.

Extension functions:

- `KubernetesClient.runIfLeader(...)`
- `KubernetesClient.runAsyncIfLeader(...)`
- `KubernetesClient.suspendRunIfLeader(...)`

Factory types may be added if needed by later Spring/auto-configuration work,
but the first PR does not need new factory integration.

### Lease Ownership Model

The backend stores a per-acquisition fencing token in Kubernetes
`spec.holderIdentity`. It does not use `LeaderElectionOptions.nodeId` directly as
the holder because two concurrent calls from the same JVM or Pod could then renew
the same Lease and both run the guarded action.

Identity mapping:

- `spec.holderIdentity`: backend ownership token used for acquire, release, and
  extend compare-and-set semantics.
- `metadata.annotations["leader.bluetape4k.io/audit-leader-id"]`: the
  user-facing audit id. The lock-name overload uses the holder token; the
  `LeaderSlot` overload uses `slot.leaderId`.
- `metadata.annotations["leader.bluetape4k.io/node-id"]`: physical node id from
  `LeaderElectionOptions.nodeId`.
- `metadata.annotations["leader.bluetape4k.io/managed-by"]`: constant
  `bluetape4k-leader-k8s` for diagnostics.

Acquire path:

1. Validate `lockName` and use it as the Kubernetes Lease name.
2. Generate the owner token for this acquisition attempt.
3. Read the current Lease.
3. If absent, create a Lease with:
   - metadata name/namespace;
   - holder identity = owner token;
   - bluetape4k audit/node annotations;
   - acquire time = now;
   - renew time = now;
   - lease duration seconds = ceil(`leaseTime`);
   - lease transitions = 0.
4. If present and holder is blank, same owner token, or expired, update it with the
   copied `metadata.resourceVersion`.
5. If present and held by another non-expired holder, retry until `waitTime`
   expires, then return `false`.
6. If create/update returns Kubernetes HTTP 409, treat it as contention and retry
   until the acquisition deadline.

Update path:

- Preserve Kubernetes optimistic concurrency by updating the typed resource read
  from the API server, including its `metadata.resourceVersion`.
- Increment `leaseTransitions` only when a non-blank holder changes to a
  different holder.
- Refresh `renewTime` on same-holder renew and takeover.
- Reset `acquireTime` when a different holder takes over.

Release path:

- Re-read the Lease.
- If the current holder is not this acquired lock's owner token, return false.
- If `minLeaseTime` still has remaining time, keep the holder and set:
  - `renewTime = now`;
  - `leaseDurationSeconds = ceil(remainingMinLeaseTime)`.
- Otherwise clear `holderIdentity` and set `renewTime = now`.
- Kubernetes 409 on release is logged and ignored because another candidate won
  the resource-version race.

Extend path:

- Re-read the Lease.
- Extend only when:
  - holder identity matches this acquired lock's owner token;
  - the lease is not expired.
- Update `renewTime = now` and `leaseDurationSeconds = ceil(requested duration)`.
- Return `ExtendOutcome.Extended(observedExpireAt)` on success.
- Return `ExtendOutcome.NotHeld` on holder mismatch, expiry, not found, or 409.
- Wrap backend exceptions as `ExtendOutcome.BackendError` through the delegate.

State path:

- Absent Lease -> `LeaderState.empty(lockName)`.
- Blank holder -> `LeaderState.empty(lockName)`.
- Expired holder -> `LeaderState.empty(lockName)`.
- Occupied holder -> `LeaderState.occupied(lockName, LeaderLease(...))`.
- `LeaderLease.auditLeaderId` uses the bluetape4k audit annotation when present,
  else the holder token.
- `LeaderLease.nodeId` uses the bluetape4k node annotation when present.
- `LeaderLease.electedAt` uses `acquireTime`.
- `LeaderLease.leaseUntil` uses `(renewTime ?: acquireTime) + leaseDurationSeconds`.

### Execution Semantics

Blocking `runIfLeader` follows the backend pattern from Mongo/Hazelcast:

1. Acquire Lease.
2. Create one `LeaderLockHandle.Real` with a shared `ExtendDelegate`.
3. Push the handle into `AopScopeAccess.withPushedSync`.
4. Start `LeaderLeaseAutoExtender` when `autoExtend=true`.
5. Execute the action exactly once.
6. Close watchdog and release in `finally`.
7. Propagate action exceptions.

The backend must override the `LeaderSlot` variants for blocking, async, and
suspend APIs. Slot calls stamp `slot.leaderId` into `LeaderLockHandle.Real` and
Lease annotations without using the interface bridge fallback.

Async `runAsyncIfLeader` uses `CompletableFuture` on the supplied executor:

- Run acquire on the executor.
- Return `completedFuture(null)` when skipped.
- On acquired path, execute the async action and release in a completion handler.
- If the returned future is cancelled or completed exceptionally, close the
  watchdog, release owner-conditionally, and propagate the completion state.

Suspend `runIfLeader`:

- Calls Fabric8 operations inside `withContext(Dispatchers.IO)`.
- Uses `currentCoroutineContext().ensureActive()` before blocking IO loops.
- Re-throws `CancellationException`.
- Releases in `withContext(NonCancellable + Dispatchers.IO)`.
- Does not use `runCatching` around suspend calls.

### Validation Rules

- `namespace` and `nodeId` must be non-blank.
- `retryDelay` must be positive.
- `lockName` must be non-blank and should be a valid Kubernetes metadata name.
  The first PR should enforce lowercase DNS-1123 label style because Kubernetes
  Lease names are object names and invalid names otherwise fail later at the API
  server.
- Lease duration is stored in seconds, so `leaseTime` and `minLeaseTime`
  conversions use ceiling seconds with a minimum of one second when a positive
  duration is present.
- Acquisition retries use full jitter bounded by `retryDelay` to avoid a hot
  resourceVersion conflict loop under contention.

### RBAC

Application RBAC should be namespace-scoped when possible.

Minimum runtime verbs:

```yaml
apiGroups: ["coordination.k8s.io"]
resources: ["leases"]
verbs: ["get", "create", "update"]
```

Optional verbs:

- `delete`: only for test cleanup or explicit operational cleanup utilities.
- `list`/`watch`: not required by the first module because it uses point reads
  and owner-conditional updates, not informer watches.

### Tests

Test resources:

- `leader-k8s/src/test/resources/junit-platform.properties`
- `leader-k8s/src/test/resources/logback-test.xml`

K3s integration tests:

- Tagged `@Tag("k8s")`.
- `test` excludes `k8s`.
- Dedicated `k8sTest` includes `k8s` and disables JUnit parallel execution.

Coverage:

- acquire executes action once;
- same-node concurrent calls do not both run because holder identity is a
  per-acquisition fencing token;
- contention returns null;
- release allows reacquire;
- expired Lease takeover succeeds;
- `minLeaseTime` retains holder until the shortened Lease expires;
- state maps absent, empty, occupied, and expired Lease states;
- `LeaderSlot` overloads stamp `slot.leaderId` into state/handle audit identity;
- action exception propagates while release still runs;
- async cancellation/exception releases owner-conditionally;
- suspend cancellation releases in `NonCancellable`;
- cleanup deletes test Lease objects in `finally`.

### Workflow

Update:

- `settings.gradle.kts`
  - include `bluetape4k-leader-k8s`.
  - map project dir to `leader-k8s`.
- `bluetape4k-leader-bom/build.gradle.kts`
  - add `api(project(":bluetape4k-leader-k8s"))`.
- `.github/workflows/ci.yml`
  - add `leader-k8s` filter output and paths.
  - add normal `:bluetape4k-leader-k8s:test` job.
  - include the job in `coverage-report` and `ci-status`.
  - keep privileged `k8sTest` out of PR CI.
- `.github/workflows/nightly-tests.yml`
  - add full/nightly `:bluetape4k-leader-k8s:k8sTest` job.
  - include it in nightly status aggregation.

## Risks

- K3s requires privileged Docker; local and standard CI may not always support it.
  Mitigation: normal `test` excludes `k8s`; full/nightly handles `k8sTest`.
- Kubernetes Lease duration precision is seconds, while core options are Kotlin
  durations. Mitigation: ceiling-second conversion and documentation.
- API server clock and client clock can drift. Mitigation: follow Kubernetes
  Lease convention using client-side `renewTime`, document clock assumption, and
  preserve resourceVersion optimistic concurrency.
- A cleared holder still leaves a Lease object. Mitigation: document this as
  operationally visible state and provide internal test cleanup only.
- The Lease object carries a fencing token in `holderIdentity`, so operators see
  a token instead of a plain pod name. Mitigation: store human-readable audit and
  node ids in annotations and document the mapping.

## Acceptance

- `leader-k8s` exists and is publishable through BOM.
- Blocking, async, and suspend single-leader APIs satisfy skip-on-contention,
  exception propagation, and owner-conditional release.
- Same-node concurrent calls cannot both run for the same Lease.
- `LeaderState` reflects useful Lease identity and expiry data.
- K3s integration tests cover #335 acceptance cases.
- README and README.ko document dependency, RBAC, options, usage, and test
  runner requirements.
- CI and nightly workflows include compile/test and privileged K3s coverage in
  the correct lanes.
- A short lesson is added after implementation.

## Step 2-R Review Notes

### Codex Multi-Perspective Review

Latest integrated findings:

| Priority | Finding | Decision |
|---|---|---|
| P1 | Using `LeaderElectionOptions.nodeId` directly as `spec.holderIdentity` can allow two concurrent same-JVM/Pod calls to treat the Lease as already owned and both execute. | Accepted. Spec now uses a per-acquisition fencing token as holder and stores audit/node identity in annotations. |
| P2 | RBAC scope was mentioned only as a README topic, not as concrete minimum verbs. | Accepted. Spec now requires namespace-scoped `get/create/update` and marks `delete`, `list`, and `watch` optional/not required. |
| P2 | ResourceVersion conflict retry needed an explicit backoff to avoid hot-looping on 409. | Accepted. Spec now requires full jitter bounded by `retryDelay`. |
| P2 | Slot overload requirements were implicit even though `leader-core` requires backend overrides for audit identity. | Accepted. Spec now requires blocking/async/suspend slot overrides and tests. |
| P2 | Async cancellation/exception release was not explicit. | Accepted. Spec now requires completion-handler release on cancel/error. |

Convergence: `P0 = 0`, `P1 = 0`.

### Claude Code Opus Advisor

Artifact: `.omx/artifacts/claude-issue-335-spec-20260521215739.md`

Result: advisor could not run because the local Claude account reported
`You're out of usage credits · resets 11pm (Asia/Seoul)`.

Decision: record the advisor gap and continue with Codex multi-perspective review
because the external CLI is installed but temporarily quota-blocked. No advisor
P0/P1 findings are available for this iteration.
