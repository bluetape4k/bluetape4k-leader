# Issue 335 leader-k8s Implementation Plan

## Step 0: Worktree

- Branch: `feat/issue-335-leader-k8s`
- Worktree: `.worktrees/feat/issue-335-leader-k8s`
- Base: `origin/develop` at `b56ff1b8`

## Step 1: Requirements

- Use `$bluetape4k-workflow` Type A Full Design because this adds a publishable
  backend module, public APIs, tests, README pairs, BOM, and workflow wiring.
- Follow `$bluetape4k-patterns` for Kotlin validation, coroutine cancellation,
  README locale updates, test resources, and final diagnostics/build checks.
- Keep #231 operator example and multi-leader Kubernetes semantics out of scope.
- Use PullMD for web-page extraction after URL discovery.

## Step 2: Ordered Implementation Tasks

1. Register the module skeleton.
   - Add `leader-k8s/build.gradle.kts`.
   - Register `:bluetape4k-leader-k8s` in `settings.gradle.kts`.
   - Add the module to `bluetape4k-leader-bom/build.gradle.kts`.
   - Add test resources: `junit-platform.properties`, `logback-test.xml`.
2. Implement internal Lease primitives.
   - `KubernetesLeaseNames` / validation helpers for DNS-1123 Lease names.
   - `KubernetesLeaseTime` helper for positive duration to ceiling seconds.
   - `KubernetesLeaseStateMapper`.
   - `KubernetesLeaseLock` with acquire/release/extend/state/delete.
   - `KubernetesLeaseLockExtendDelegate`.
   - `KubernetesBackendErrorClassifier` if Fabric8 exceptions need transient
     classification beyond core behavior.
3. Implement public APIs.
   - `KubernetesLeaseOptions`.
   - `KubernetesLeaseLeaderElector`.
   - `KubernetesLeaseSuspendLeaderElector`.
   - Extension functions for blocking, async, and suspend usage.
   - Override `LeaderSlot` variants for blocking/async/suspend audit identity.
   - Document client lifecycle in KDoc: callers own and close the supplied
     `KubernetesClient`; electors do not close it.
4. Add non-container unit tests for the default `test` lane.
   - Lease name validation and duration ceiling conversion.
   - `KubernetesLeaseStateMapper` for absent-like, blank holder, expired,
     occupied, annotation-backed audit/node identity, and missing time fields.
   - Backend error classifier behavior for Fabric8 conflict/timeout/security
     exceptions when implemented.
   - Options validation.
5. Add K3s tests.
   - `KubernetesLeaseLeaderElectorK3sTest`.
   - `KubernetesLeaseSuspendLeaderElectorK3sTest`.
   - Cover acquire, contention, release/reacquire, expiry takeover, state
     mapping, same-node concurrent skip, slot audit identity, action exception,
     async failure/cancel release, suspend cancellation release, and cleanup.
   - Keep all K3s tests tagged `@Tag("k8s")`.
6. Wire Gradle test isolation.
   - Default `test` excludes `k8s`.
   - Default `test` still runs non-container unit tests from task 4.
   - Add `k8sTest` task including `k8s`.
   - Disable JUnit parallel execution for `k8sTest`.
7. Add documentation.
   - `leader-k8s/README.md`.
   - `leader-k8s/README.ko.md`.
   - Include dependency, RBAC, options, identity mapping, usage, state behavior,
     client lifecycle ownership, and test runner requirements.
8. Wire workflows.
   - `.github/workflows/ci.yml`: path filter output, module test job,
     coverage/status `needs`.
   - `.github/workflows/nightly-tests.yml`: full/nightly K3s job and status
     `needs`.
9. Add lesson.
   - `docs/lessons/2026-05-21-issue-335-leader-k8s.md`.

## Step 3: Validation Plan

Run in order:

1. IDE diagnostics/inspection for touched Kotlin files when available.
2. `./gradlew projects --no-daemon`
3. `./gradlew :bluetape4k-leader-k8s:compileKotlin :bluetape4k-leader-k8s:compileTestKotlin --no-daemon`
4. `./gradlew :bluetape4k-leader-k8s:test --no-daemon`
5. `./gradlew :bluetape4k-leader-k8s:k8sTest --no-daemon`
6. `./gradlew build -x test -x koverVerify --parallel --no-daemon`
7. `actionlint .github/workflows/ci.yml .github/workflows/nightly-tests.yml`

If privileged Docker/K3s is unavailable locally, compile and default tests still
run; report the `k8sTest` environment gap explicitly.

## Step 4: Review Gates

- Step 3-R plan review:
  - Check implementation ordering against the approved spec.
  - Check test coverage for all owner-token, slot, async, and suspend release
    paths.
  - Check workflow wiring against repo-local `AGENTS.md`.
- Step 6-R code review:
  - Focus on resourceVersion conflict handling, owner-token release/extend,
    coroutine cancellation, `runCatching` avoidance around suspend calls,
    RBAC/docs drift, and workflow coverage.
  - Claude Code advisor should be retried if credits have reset; record gap if
    still unavailable.

## Step 5: Stop Conditions

- Complete only after implementation, docs, lesson, targeted validation, commit,
  push, and PR are done.
- Block if Fabric8 7.6.1 cannot compile Lease APIs already used by the existing
  K3s example.
- Block if `K3sServer.Launcher.k3s` is missing from resolved testcontainers
  artifacts.
- Do not merge the PR automatically; merge remains user-requested.

## Step 3-R Review Notes

### Codex Plan Review

Latest integrated findings:

| Priority | Area | Finding | Decision |
|---|---|---|---|
| P1 | Tests | All meaningful behavior tests were assigned to K3s, which would make the normal `test` lane weak or empty. | Accepted. Added non-container unit tests for validation, time conversion, state mapping, options, and classifier behavior. |
| P2 | Lifecycle | Plan did not state KubernetesClient ownership. | Accepted. Public API and README tasks now document caller-owned client lifecycle. |

Convergence: `P0 = 0`, `P1 = 0`.

### Claude Code Opus Advisor

Artifact: `.omx/artifacts/claude-issue-335-plan-20260521220106.md`

Result: advisor could not run because the local Claude account reported
`You're out of usage credits · resets 11pm (Asia/Seoul)`.

Decision: record the advisor gap and continue with Codex plan review because the
external CLI is installed but temporarily quota-blocked. No advisor P0/P1
findings are available for this iteration.
