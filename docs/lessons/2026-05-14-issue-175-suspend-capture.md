## Context

Issue #175 reported that `CaptureScope.runWithCaptureSuspend` used `ThreadLocal`
capture around a suspend action. Dispatcher hops can resume cleanup on a
different carrier thread, leaving a stale handle on the original thread.

## Decision

Suspend group election no longer uses `CaptureScope`, `AopScopeAccess.setCapture`,
or `LeaderLockHandleCapture`. It relies only on `LockHandleElement` in the
coroutine context. `CaptureScope` is now sync-only and its tests cover only
synchronous ThreadLocal capture.

## Outcome

`LocalSuspendLeaderGroupElector` calls the suspend action directly inside
`withContext(LockHandleElement(handle))`. Backend suspend group electors now use
only `withContext(AopScopeAccess.createLockHandleElement(handle))` as well.
A new stress test checks IO and Default dispatcher hops and verifies that
`LeaderLockHandleCapture.poll()` remains null.

## Verification

- `./gradlew :leader-core:test --tests 'io.bluetape4k.leader.internal.CaptureScopeTest' --tests 'io.bluetape4k.leader.coroutines.LocalSuspendLeaderGroupElectorCaptureTest' --console=plain`
  - 5 tests passed.
- `./gradlew :leader-core:test --console=plain`
  - 605 tests passed.
- Claude Tier 4 advisor review found the same ThreadLocal-around-suspend pattern
  in backend suspend group electors. The finding was accepted and fixed.
- `./gradlew :leader-core:test :leader-spring-boot:compileKotlin :leader-redis-lettuce:compileTestKotlin :leader-redis-redisson:compileTestKotlin :leader-mongodb:compileTestKotlin :leader-hazelcast:compileTestKotlin :leader-zookeeper:compileTestKotlin :leader-exposed-r2dbc:compileTestKotlin --console=plain`
  - Build successful; `leader-core` 605 tests passed.
- Step 7-R dual PR review ran after PR creation:
  - Codex PR review: approve, no P0/P1/P2/P3 findings.
  - Claude PR review: initial comment for missing Spring AOP verification, then
    approve after `./gradlew :leader-spring-boot:test --console=plain` passed
    280 tests and `pollCapture` was confirmed absent from suspend group aspect
    runtime paths.
  - GitHub CI was green and merge state was clean; PR remained draft.

## Future Guidance

Do not add ThreadLocal capture helpers to suspend electors. Use
`LockHandleElement` for suspend lock handle propagation and reserve
`CaptureScope.runWithCapture` for synchronous electors only.

When Step 7-R is required, leave both a concise PR comment and a formal GitHub
review entry. A plain issue comment is useful evidence but does not populate the
PR review timeline.
