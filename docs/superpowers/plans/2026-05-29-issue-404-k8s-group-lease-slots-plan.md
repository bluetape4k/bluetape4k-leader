# Issue 404 Kubernetes Lease-per-slot group election plan

## Scope

Implement the approved Lease-per-slot group election design for `leader-k8s`.

Branch: `feat/404-k8s-group-lease-slots`
Worktree: `.worktrees/feat-404-k8s-group-lease-slots`
Base: `origin/develop` at `a8f0942f`

## Tasks

1. Add shared group support types.
   - Add `KubernetesLeaseGroupOptions`.
   - Add internal slot name and group state helpers.
   - Reuse `KubernetesLeaseLock`, `KubernetesLeaseLockExtendDelegate`, and `KubernetesLeaseStateMapper`.

2. Implement blocking group elector.
   - Add `KubernetesLeaseLeaderGroupElector`.
   - Implement `activeCount`, `availableSlots`, and `state`.
   - Implement `runIfLeader(lockName)`, `runIfLeader(slot)`, and `runIfLeaderResult(slot)`.
   - Add `KubernetesClient.runIfLeaderGroup` and `runAsyncIfLeaderGroup` extensions.

3. Implement suspend group elector.
   - Add `KubernetesLeaseSuspendLeaderGroupElector`.
   - Wrap acquire/release/state calls in `Dispatchers.IO`.
   - Release in `NonCancellable + Dispatchers.IO`.
   - Add `KubernetesClient.suspendRunIfLeaderGroup` extension.

4. Add K3s integration tests.
   - Blocking group acquire up to `maxLeaders`.
   - Extra contender skipped while all slots are occupied.
   - Release/reacquire.
   - Expired slot takeover.
   - Slot audit identity appears in state and result.
   - Suspend cancellation cleanup.
   - Cleanup helper deletes all derived slot Leases.

5. Update docs and lesson.
   - Update `leader-k8s/README.md`.
   - Update `leader-k8s/README.ko.md`.
   - Add `docs/lessons/2026-05-29-issue-404-k8s-group-lease-slots.md`.

6. Verify.
   - `./gradlew :bluetape4k-leader-k8s:compileKotlin :bluetape4k-leader-k8s:compileTestKotlin --no-daemon`
   - `./gradlew :bluetape4k-leader-k8s:test --no-daemon`
   - `./gradlew :bluetape4k-leader-k8s:k8sTest --no-daemon --max-workers=1`
   - `./gradlew build -x test -x k8sTest --no-daemon`
   - `git diff --check`

7. Review and publish.
   - Run local 7-Tier code review on the implementation diff.
   - Fix any P0/P1 findings and rerun affected tests.
   - Commit with Lore protocol.
   - Push and open PR for `#404` with K3s evidence and any flake notes.

## Risk Controls

- Do not change `leader-core` interfaces unless implementation proves impossible without it.
- Do not run separate Testcontainers Gradle processes concurrently.
- Treat state as observability only.
- Preserve caller ownership of `KubernetesClient`.
- Keep normal production release non-deleting.

## Step 3-R Plan Review

| Tier | Scope | P0 | P1 | P2 | P3 | Notes |
|---|---|---:|---:|---:|---:|---|
| 1 Security | RBAC and input validation | 0 | 0 | 0 | 0 | Slot Lease names are derived and validated; no secret annotations added. |
| 2 Ops/SRE | lifecycle and cleanup | 0 | 0 | 0 | 0 | Cleanup helper limited to tests/docs; production release preserves Lease records. |
| 3 Structure | module boundaries | 0 | 0 | 0 | 0 | Implementation stays in `leader-k8s`; core API unchanged. |
| 4 Kotlin/API | sync/async/suspend parity | 0 | 0 | 0 | 0 | Blocking and suspend electors both planned; async bridge covered by blocking elector. |
| 5 Tests/types | behavior coverage | 0 | 0 | 0 | 0 | Every acceptance criterion maps to a named K3s test. |
| 6 Performance/stability | retry budget and K3s sequencing | 0 | 0 | 0 | 0 | Sequential K3s command named; random slot start in design. |
| 7 Docs/release | README, lesson, PR evidence | 0 | 0 | 0 | 0 | English/Korean docs and lesson included. |

Step 3-R status: PASS. P0 = 0, P1 = 0.
