# Issue 213 ActionFailed Result Contract

## Context

`runIfLeaderResult` needed a clear outcome for action failures while preserving the simple `runIfLeader`
contract.

## Decision

Use `LeaderRunResult.ActionFailed(cause)` for failures after leadership is acquired and the user action
starts. Keep `runIfLeader` unchanged: contention returns `null`, action failure throws.

Do not represent cancellation as a result. Blocking and suspend result APIs must rethrow
`CancellationException`; async and virtual-thread result APIs must complete exceptionally instead of returning
`ActionFailed`.

## Outcome

KDoc now documents `Elected`, `Skipped`, and `ActionFailed`. Core sync, suspend, async, virtual-thread,
Lettuce, and Redisson result APIs return `ActionFailed` for action exceptions after leadership is acquired.
Spring AOP rethrows `ActionFailed.cause` to preserve existing advice behavior.

Claude review found three P1 gaps before PR: Redis backend result tests were missing, blocking APIs wrapped
`InterruptedException`, and async/virtual-thread cancellation docs implied `isCancelled()` semantics that
`CompletableFuture.handle` does not guarantee. The implementation now rethrows `InterruptedException` after
restoring the interrupt flag, docs describe exceptional completion for async/virtual cancellation, and Lettuce
plus Redisson sync/suspend single/group tests cover `ActionFailed` and `CancellationException`.

## Verification

- `./gradlew :leader-core:test --no-daemon` passed with 624 tests before the Redis review follow-up; after
  adding interruption regressions, the targeted follow-up run executed 626 core tests successfully before
  failing on overly strict Lettuce suspend exception identity assertions.
- `./gradlew :leader-core:compileKotlin :leader-redis-lettuce:compileKotlin :leader-redis-redisson:compileKotlin :leader-spring-boot:compileKotlin --no-daemon` passed. Spring AspectJ emitted existing `unresolvableMember` / `adviceDidNotMatch` warnings.
- `./gradlew :leader-redis-lettuce:test :leader-redis-redisson:test --tests io.bluetape4k.leader.lettuce.LettuceSuspendLeaderElectorTest --tests io.bluetape4k.leader.lettuce.LettuceSuspendLeaderGroupElectorTest --tests io.bluetape4k.leader.redisson.RedissonSuspendLeaderElectorTest --tests io.bluetape4k.leader.redisson.RedissonSuspendLeaderGroupElectorTest --no-daemon` passed: Lettuce 197 tests, Redisson 22 tests.
- `./gradlew :leader-core:test :leader-redis-lettuce:test :leader-redis-redisson:test --tests io.bluetape4k.leader.LeaderRunResultTest --tests io.bluetape4k.leader.lettuce.LettuceLeaderElectionTest --tests io.bluetape4k.leader.lettuce.LettuceSuspendLeaderElectorTest --tests io.bluetape4k.leader.lettuce.LettuceLeaderGroupElectionTest --tests io.bluetape4k.leader.lettuce.LettuceSuspendLeaderGroupElectorTest --tests io.bluetape4k.leader.redisson.RedissonLeaderElectionTest --tests io.bluetape4k.leader.redisson.RedissonSuspendLeaderElectorTest --tests io.bluetape4k.leader.redisson.RedissonLeaderGroupElectionTest --tests io.bluetape4k.leader.redisson.RedissonSuspendLeaderGroupElectorTest --no-daemon` passed: Redisson 64 tests; core and Lettuce tasks were up-to-date from prior verification.
- IntelliJ build diagnostics reported zero errors.
- Repo inventory found 24 production result API files and zero files missing `ActionFailed` handling.
- Repo search found no stale alternative result names.
- Claude CLI review succeeded on the 5-minute retry and the P1 items were addressed. Artifact: `.omx/artifacts/claude-actionfailed-result-20260514183409.md`.

## Future Guidance

When adding result APIs for new backends, distinguish only three value outcomes: elected, skipped, and
action failed. Keep backend/acquire failures as exceptions unless a separate backend-result contract is
explicitly designed. Always test `CancellationException` and `InterruptedException` separately from action
failure; broad `catch (Exception)` blocks can silently convert cancellation/interruption into an action
failure. For `CompletableFuture` APIs, document cancellation as exceptional completion unless the code
explicitly calls `cancel()`.
