# Issue 227 leader-etcd Implementation Plan

## Step 0: Worktree

- Repository: `bluetape4k-leader`
- Branch: `feat/issue-227-leader-etcd-design`
- Worktree: `.worktrees/feat-issue-227-leader-etcd-design`
- Base: `develop` at `b3e9ffd`
- Spec: `docs/superpowers/specs/2026-05-22-issue-227-leader-etcd-design.md`

## Step 1: Requirements

- Use `$bluetape4k-workflow` Type A Full Design because this adds a publishable
  backend module, third-party dependency, public APIs, integration tests, docs,
  BOM, and workflow wiring.
- Follow `$bluetape4k-patterns` for Kotlin validation, coroutine cancellation,
  README locale updates, test resources, and final diagnostics/build checks.
- External research is stored in shared wiki research and indexed by qmd:
  `~/work/bluetape4k/wiki/research/2026-05-22-issue-227-etcd-official-docs-pullmd.md`.
- Preferred test fixture is `EtcdServer.Launcher` in `bluetape4k-testcontainers`.
  `bluetape4k-projects` currently does not expose that fixture, so it is a
  prerequisite lane before merging `leader-etcd`.

## Step 2: Prerequisite Lane

1. Create or reuse a `bluetape4k-projects` issue for `EtcdServer.Launcher`.
   - Scope: add singleton Testcontainers wrapper for etcd, endpoint helpers, and
     minimal launch smoke test.
   - Follow the existing `ConsulServer` / `RedisServer` launcher pattern.
2. Implement and publish the fixture before merging `leader-etcd`.
   - Verify `./gradlew :bluetape4k-testcontainers:test` or the affected
     testcontainers module command.
   - Publish or otherwise make the SNAPSHOT available to `bluetape4k-leader` CI.
3. Keep the `leader-etcd` PR draft until the fixture artifact is available.
4. If this lane is blocked, use `io.etcd:jetcd-test` only as an explicitly
   documented fallback and keep a follow-up issue to replace it with
   `EtcdServer.Launcher`.

## Step 3: Ordered Implementation Tasks

1. Register the module skeleton.
   - Add `leader-etcd/build.gradle.kts`.
   - Register `:bluetape4k-leader-etcd` in `settings.gradle.kts`.
   - Add `jetcd = "0.8.6"` and `jetcd-core` to `gradle/libs.versions.toml`.
   - Add `kotlinx-coroutines-jdk8`; the current catalog lacks it and suspend
     APIs need `CompletableFuture.await()`.
   - Add `:bluetape4k-leader-etcd` to `bluetape4k-leader-bom/build.gradle.kts`.
   - Declare the module Kover threshold explicitly. Start at 80% unless the
     implementation proves container-only branches make that unrealistic; any
     lower threshold must be justified in the PR and lesson.
   - Add test resources: `junit-platform.properties`, `logback-test.xml`.
   - Run a dependency-resolution smoke gate before deeper implementation:
     `./gradlew :bluetape4k-leader-etcd:dependencies --configuration runtimeClasspath --no-daemon`
     and `./gradlew :bluetape4k-leader-etcd:compileKotlin --no-daemon`.
     Compare Netty/gRPC/Vert.x versions against existing runtime classpaths
     where relevant and record any forced-version decision before coding on top
     of jetcd.
2. Implement key and time helpers.
   - `EtcdKeyEncoder`: reversible UTF-8 percent-encoding outside
     `[A-Za-z0-9._-]`.
   - `EtcdLeaderPaths`: default prefix, single path, group slot path.
   - `EtcdLeaseTime`: duration-to-ceil-seconds and `leaseTime / 3` cadence with
     ±10% jitter.
   - Unit tests for encoding collisions, slash encoding, Unicode names, and
     duration ceiling.
3. Implement client boundary primitives.
   - `EtcdLeaderElectionOptions`.
   - `EtcdBackendErrorClassifier`.
     Enumerate and test at least:
     `etcdserver: requested lease not found`, `etcdserver: lease not found`,
     `etcdserver: key not found`, `Unauthenticated`, `PermissionDenied`,
     `Unavailable`, `DeadlineExceeded`, `Cancelled`, `ClosedChannelException`,
     and general `StatusRuntimeException`.
   - `EtcdLeaseHandle` holding lease id, original `ByteSequence` ownership key,
     stable string token for `LeaderLockHandle.Real.token`, lock name, and
     release state.
     Use deterministic collision-resistant encoding, such as base64url or hex,
     for non-UTF-8-safe ownership bytes.
   - `EtcdLockClient` wrapping jetcd lease/lock/KV calls behind a narrow
     internal API.
   - Security boundary:
     - `EtcdLeaderElectionOptions` must not carry credentials or TLS material.
     - TLS/auth/user/password/headers are entirely caller-owned jetcd `Client`
       builder concerns.
     - classify `Unauthenticated` and `PermissionDenied` as non-retried backend
       errors, not internal keepalive retries.
   - Timeout cleanup behavior:
     - enforce one monotonic overall acquisition budget;
     - on `Lock.lock` timeout, revoke lease immediately;
     - attach late completion cleanup that unlocks any late ownership key and
       revokes the lease again;
     - log already-revoked/already-deleted cleanup as debug-level expected
       cleanup.
4. Implement single-leader electors.
   - `EtcdLeaderElector`.
   - `EtcdVirtualThreadLeaderElector`.
   - `EtcdSuspendLeaderElector`.
   - Extension functions for `Client`.
   - Factories for blocking and suspend APIs.
   - `LeaderRunResult` overloads and `LeaderSlot` audit identity overloads.
   - Match the overload set and naming style of the existing Lettuce/Redisson
     backend APIs for surface symmetry.
   - Suspend public entry points must preserve `CancellationException` and use
     `CompletableFuture.await()` without production `runBlocking`; only wrap
     genuinely blocking fallback work in `withContext(Dispatchers.IO)`. User
     actions run in the caller's coroutine context.
5. Implement extend delegates.
   - `EtcdLockExtendDelegate` and suspend counterpart when needed.
   - Use `Lease.keepAliveOnce(leaseId)` as the hot path.
   - One immediate retry on keepalive failure.
   - Return `NotHeld` when lease/key is gone; `BackendError` for backend
     failures.
   - Do not create a new lease during extension.
6. Implement state mapping.
   - `EtcdLeaderStateMapper`.
   - Linearizable current-state query for single lock.
   - Document and test v1 audit fallback to opaque ownership key if safe
     sidecar metadata is not implemented.
   - If rich audit metadata is deferred, create or record a follow-up issue for
     Election service or sidecar metadata before Step 6-R so README/KDoc can
     link it.
7. Implement group electors.
   - `EtcdLeaderGroupElector`.
   - `EtcdSuspendLeaderGroupElector`.
   - Randomized start slot with modular traversal.
   - Use one monotonic overall wait budget. For each remaining slot, compute
     `perSlotBudget = max(50.milliseconds, remainingBudget / unattemptedSlots)` and pass
     `min(remainingBudget, perSlotBudget)` to that slot attempt so one contended
     slot cannot consume the entire group acquisition window.
   - Implement `activeCount`, `availableSlots`, and `state`.
8. Implement watch-backed event publisher.
   - Closeable publisher/decorator that implements `LeaderElectionEventPublisher`.
   - Default internal `SupervisorJob + Dispatchers.IO` scope.
   - Overload accepting caller-provided `CoroutineScope`.
   - Watch single/group prefix, revalidate state for ambiguous events, and emit
     lifecycle events.
   - Use a bounded event buffer/drop policy compatible with existing
     `LeaderElectionEventPublisher` semantics and test slow-consumer behavior.
   - Restart on recoverable watch failures with exponential backoff 200 ms to
     5 s, max 10 consecutive failures, then close and log terminal failure.
   - Handle watch compaction (`ErrCompacted` or equivalent) by restarting from a
     fresh current-state revalidation instead of replaying from the compacted
     revision.
   - Prove restart behavior with an injected watch wrapper/fake that fails N
     times then succeeds; keep real container restart coverage as nightly-only
     if it is stable enough.
   - Ensure closing the publisher does not close the caller-owned jetcd `Client`.
9. Add unit tests.
   - Options validation.
   - Key encoding/path mapping.
   - Lease time/cadence/jitter bounds.
     Use deterministic clocks or injected randomness; do not rely on wall-clock
     sleeps for cadence assertions.
   - Error classifier mapping.
     Include non-retried auth errors and expected cleanup errors; assert
     late unlock/revoke after timeout does not log WARN/ERROR for already
     revoked/deleted resources.
   - State mapper for empty, occupied, expired/missing lease, and opaque audit
     fallback.
   - Timeout cleanup using a fake/internal `EtcdLockClient`.
   - Ownership key conversion: original `ByteSequence` is used for unlock while
     the shared handle receives a stable `String` token.
     Include non-UTF-8-safe byte sequences and assert deterministic,
     collision-resistant token strings.
   - Path traversal/adversarial encoding inputs: `../`, leading slash, repeated
     slash, NUL, Unicode, and percent-like payloads.
   - Group slot traversal order and fixed budget slicing formula.
10. Add real etcd integration tests.
   - Shared `AbstractEtcdLeaderTest` using `EtcdServer.Launcher.etcd` when
     available.
   - Single leader: acquire, contention returns `null`, release/reacquire,
     action exception propagates, `LeaderRunResult`, `LeaderSlot` audit path.
   - Lease behavior: expiry takeover, auto-extension keeps leadership,
     min-lease-time delays release, timed-out late acquisition cleanup.
   - Suspend: acquisition, contention, cancellation cleanup,
     `CancellationException` rethrow.
   - Group: maxLeaders bound, slot release/reacquire, state/active count, suspend
     group cancellation cleanup.
   - Watch publisher: observes create/delete changes, proves restart through the
     injected watch wrapper/fake, idempotent close/double-close behavior, no emissions
     after terminal close, close stops events without closing `Client`, and
     double-close does not emit WARN/ERROR noise.
   - Nightly stress: at least 3 candidates, fewer slots, and at least 20
     acquire/release cycles to prove the `maxLeaders` bound under contention.
11. Add documentation.
   - `leader-etcd/README.md`.
   - `leader-etcd/README.ko.md`.
   - Update root `README.md` and `README.ko.md` backend tables, dependency
     snippets, and capability matrix.
   - Include caller-owned `Client` lifecycle, TLS/auth/credential configuration
     responsibility, key prefix, lease/auto-extend behavior, group semantics,
     watch event caveats, and Testcontainers runner notes.
   - Record that Spring Boot auto-configuration is intentionally deferred from
     the first core backend PR and create/link a follow-up issue, or leave #227
     open with that unchecked item.
12. Wire workflows.
   - `.github/workflows/ci.yml`: paths-filter output, `test-leader-etcd` job,
     summary/coverage `needs`.
   - `.github/workflows/nightly-tests.yml`: real etcd integration job and
     summary/coverage `needs`.
   - Run `actionlint` after editing workflows.
13. Add lesson.
   - `docs/lessons/2026-05-22-issue-227-leader-etcd.md`.
   - Include research path, Lock service decision, timeout cleanup, watch event
     limits, and test fixture prerequisite.
   - Include any deferred follow-up issues for Spring Boot auto-configuration or
     rich audit metadata.
   - Include Lock service vs Election service vs raw KV transaction trade-offs
     and any gRPC/Netty/Vert.x dependency-resolution outcome.

## Step 4: Validation Plan

Run in order:

1. IDE diagnostics for touched Kotlin files when available.
2. `./gradlew projects --no-daemon`
3. `./gradlew :bluetape4k-leader-etcd:compileKotlin :bluetape4k-leader-etcd:compileTestKotlin --no-daemon`
4. `./gradlew :bluetape4k-leader-etcd:test --no-daemon`
5. `./gradlew :bluetape4k-leader-etcd:koverXmlReport --no-daemon`
6. `./gradlew :bluetape4k-leader-etcd:koverVerify --no-daemon`
7. `./gradlew build -x test -x koverVerify --parallel --no-daemon`
8. `actionlint .github/workflows/ci.yml .github/workflows/nightly-tests.yml`
9. Real etcd integration command:
   - use `./gradlew :bluetape4k-leader-etcd:test --no-daemon` unless Step 6-R
     explicitly approves and documents a dedicated `etcdTest` source set.

If Docker or the prerequisite `EtcdServer.Launcher` artifact is unavailable,
compile and unit tests still run; report the integration-test environment gap
explicitly and keep the PR draft.

## Step 5: Review Gates

- Step 3-R plan review:
  - Confirm every spec acceptance criterion maps to an implementation or test
    task.
  - Confirm `EtcdServer.Launcher` prerequisite is represented as a merge/publish
    gate.
  - Confirm group acquisition has one overall monotonic budget and bounded
    per-slot attempts.
  - Confirm watch publisher lifecycle, backoff, and close semantics are testable.
- Step 6-R code review:
  - Focus on lease cleanup, late future completion, owner-safe release/extend,
    coroutine cancellation, watch backpressure/restart, path encoding, client
    lifecycle ownership, and workflow wiring.
  - Enforce the suspend-call `runCatching {}` ban, explicit
    `CancellationException` rethrow, no happy-path linearizable read before
    keepalive, compaction recovery through fresh state revalidation, stable
    ownership-token encoding for arbitrary bytes, and silent idempotent
    double-close behavior.
  - Run current Codex review plus Claude Code CLI review on the same diff/scope.

## Step 6: Stop Conditions

- Continue only after Step 3-R plan review has `P0 = 0` and `P1 = 0`.
- Block implementation merge if `EtcdServer.Launcher` is neither available nor
  explicitly replaced by a documented fallback.
- Convert the PR from draft to ready only after the `EtcdServer.Launcher`
  artifact is verified resolvable from `bluetape4k-leader` CI, or after an
  approved fallback is explicitly documented.
- Do not merge the PR automatically; merge remains user-requested.

## Step 3-R Review Notes

### Codex Plan Review

| Priority | Finding | Decision |
|---|---|---|
| P1 | `LeaderLockHandle.Real.token` is `String`, while jetcd Lock returns a `ByteSequence` ownership key. | Accepted. Plan now separates the original `ByteSequence` ownership key from the stable string handle token and adds a conversion test. |
| P1 | `kotlinx-coroutines-jdk8` is absent from the current version catalog but suspend APIs need `CompletableFuture.await()`. | Accepted. Plan now requires adding the catalog alias. |
| P1 | Spring Boot auto-configuration is deferred from the first core backend PR even though #227 mentions it. | Accepted with scope control. Plan now requires a follow-up/link or leaving #227 open with that unchecked item. |

### Claude Code Opus Advisor

Artifact: `.omx/artifacts/claude-issue-227-plan-20260522033834.md`

| Priority | Finding | Decision |
|---|---|---|
| P0 | Pre-implementation gRPC/Netty/Vert.x dependency-resolution gate was missing. | Accepted. Step 3.1 now requires runtimeClasspath dependency inspection and compile smoke before deeper implementation. |
| P1 | Step 2-R rerun decisions needed concrete plan tasks, including group per-slot budget formula, expected cleanup errors, and audit fallback follow-up timing. | Accepted. Step 3.3, 3.6, 3.7, and 3.9 now pin classifier/test/follow-up/budget behavior. |
| P1 | `koverVerify` was absent from validation, and module threshold was undeclared. | Accepted. Step 3.1 now declares threshold policy; Step 4 now runs `koverVerify`. |
| P1 | TLS/auth configuration boundary lacked an API-surface task. | Accepted. Step 3.3 now states credentials/TLS remain caller-owned `Client` concerns and auth errors are non-retried backend errors. |
| P1 | Watch restart proof and several test edges were underspecified. | Accepted. Step 3.8, 3.9, and 3.10 now require bounded buffer behavior, injected restart proof, deterministic cadence tests, adversarial key encoding, and silent double-close checks. |

Rerun artifact: `.omx/artifacts/claude-issue-227-plan-rerun-20260522090604.md`

Rerun result:

| Priority | Finding | Decision |
|---|---|---|
| P0/P1 | Prior blocking findings are closed. | Step 3-R may close with `P0 = 0`, `P1 = 0`. |
| P2 | Watch compaction recovery should be explicit. | Accepted. Step 3.8 and Step 5 now require fresh state revalidation after compaction. |
| P2 | Ownership `ByteSequence` to `String` token stability should cover arbitrary bytes. | Accepted. Step 3.3 and Step 3.9 now require deterministic collision-resistant token encoding tests. |
| P3 | Integration command should be pinned before implementation review. | Accepted. Step 4 now defaults to `:bluetape4k-leader-etcd:test`; `etcdTest` requires Step 6-R approval. |
| P3 | Nightly stress needs a numeric floor. | Accepted. Step 3.10 now requires at least 3 candidates and 20 acquire/release cycles. |

Convergence: `P0 = 0`, `P1 = 0`. Step 3-R is closed.
