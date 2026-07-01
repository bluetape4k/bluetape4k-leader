# Issue 513 Review - Redisson Owner-Atomic Extend

## Scope

- Issue: #513, `P1: Redisson lock extension is not owner-atomic`
- Module: `leader-redis-redisson`
- Target behavior: replace check-then-expire renewal with a Redis-side owner check and TTL update.

## Source Evidence

- Resolved Redisson runtime is `org.redisson:redisson:4.4.0`.
- `javap` against the local Redisson 4.4.0 artifact confirms:
  - `org.redisson.RedissonBaseLock.getLockName(long)` exists and returns the owner hash field shape Redisson uses for lock ownership.
  - `org.redisson.RedissonObject.getRawName()` exists and provides the raw Redis key name.
- The implementation uses `redissonClient.getScript(StringCodec.INSTANCE)` so script arguments are encoded like Redisson lock owner fields instead of with the default object codec.

## Code-Pattern Audit

- MockK test doubles are class-level fields in `RedissonOwnerAtomicExtendDelegateTest`.
- `@BeforeEach` resets the class-level mocks with `clearMocks(scriptClient, keys, script, scriptResult)`.
- No method-local `mockk(...)` or `spyk(...)` remains in the new test.
- Suspend regression test uses `runSuspendIO`, not `runTest`, because it exercises real Redisson/Testcontainers-backed objects.
- Assertions use `bluetape4k-assertions`.
- No ad hoc thread, executor, sleep, coroutine stress loop, `!!`, JUnit assertion API, or `kotlin.test` assertion API was introduced.

## Verification

- Pattern grep:
  - Remaining `mockk(...)` matches are only class-level fields.
  - Remaining `runCatching` match is an existing KDoc warning, not executable code.
- `./gradlew :bluetape4k-leader-redis-redisson:test --tests '*RedissonOwnerAtomicExtendDelegateTest' --no-parallel`
  - `BUILD SUCCESSFUL in 13s`
- `./gradlew :bluetape4k-leader-redis-redisson:test --tests '*RedissonExtendDelegateReferenceTest' --tests '*RedissonLockExtenderContractTest' --tests '*RedissonSuspendLockExtenderContractTest' --no-parallel`
  - `BUILD SUCCESSFUL in 4s`
- `./gradlew :bluetape4k-leader-redis-redisson:test --no-parallel`
  - `BUILD SUCCESSFUL in 19s`
- `git diff --check`
  - pass

## Notes

- The regression test simulates the race deterministically through `RScript` return values instead of adding stress loops. This fits the issue because the race is removed by one Redis Lua operation, and the existing contract tests cover sync/suspend extender behavior through the module's Redisson test infrastructure.
