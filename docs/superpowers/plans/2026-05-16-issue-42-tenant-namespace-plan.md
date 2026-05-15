# Issue 42 Tenant Lock Namespace Plan

## Scope

Implement tenant lock namespace decorators in `leader-core` and document the user-facing API in the README locale set.

## Tasks

1. Add `TenantLockNamespace` in `io.bluetape4k.leader`.
   - Validate `tenantId` and `prefix`.
   - Reject `:` in tenant id, prefix, and caller-facing tenant lock names.
   - Generate `prefix:tenantId:lockName`.
   - Validate the final lock name with `validateLockName()`.
   - Report remaining lock-name length budget when generated names exceed 255 chars.

2. Add blocking wrappers in `TenantScopedLeaderElectors.kt`.
   - `TenantScopedLeaderElector : LeaderElector`
   - `TenantScopedLeaderGroupElector : LeaderGroupElector`
   - Extension functions for `LeaderElector` and `LeaderGroupElector`.
   - Override state, sync, async, result, and slot overloads where needed to preserve tenant scoping.

3. Add coroutine wrappers in `coroutines/TenantScopedSuspendLeaderElectors.kt`.
   - `TenantScopedSuspendLeaderElector : SuspendLeaderElector`
   - `TenantScopedSuspendLeaderGroupElector : SuspendLeaderGroupElector`
   - Extension functions for suspend single/group electors.
   - Preserve cancellation by direct delegation.

4. Add virtual-thread wrappers in `TenantScopedVirtualThreadLeaderElectors.kt`.
   - `TenantScopedVirtualThreadLeaderElector : VirtualThreadLeaderElector`
   - `TenantScopedVirtualThreadLeaderGroupElector : VirtualThreadLeaderGroupElector`
   - Extension functions for virtual-thread single/group electors.
   - Override state, result, and slot overloads.

5. Add tests.
   - Namespace formatting and validation.
   - Blocking single/group run and state translation.
   - Async translation and skipped behavior.
   - Slot overload preserves `leaderId` while namespacing `lockName`.
   - Suspend single/group translation and cancellation propagation.
   - Virtual-thread single/group translation.

6. Update README locale set.
   - Add concise English and Korean usage examples.
   - State separator/validation behavior.

7. Verification and review.
   - IDE diagnostics for touched Kotlin files.
   - `./gradlew :leader-core:test --tests 'io.bluetape4k.leader.TenantScopedLeaderElectorsTest' --tests 'io.bluetape4k.leader.coroutines.TenantScopedSuspendLeaderElectorsTest' --no-configuration-cache --console=plain`
   - Claude advisor review for public API/coroutine scope.
   - Tier 4 local review with P0/P1 convergence.

8. PR hygiene.
   - Add lessons entry.
   - Commit spec/plan before implementation or in the same branch before PR.
   - Create draft PR with issue link and DoD checklist.
   - Add PR comment and formal review entry.
   - Check CI status rollup; do not merge.

## Risks

- `LeaderState.lockName` returning the namespaced backend lock may surprise callers. Keep it explicit in README and KDoc.
- Custom prefixes can collide with business lock names if callers choose weak conventions. Validate prefix and document separator behavior.
- Adding wrappers over slot overloads must preserve `leaderId`; only `lockName` changes.
