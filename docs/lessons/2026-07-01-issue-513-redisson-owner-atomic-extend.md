# Issue 513 - Redisson Owner-Atomic Extend

## Context

Issue #513 exposed a race in Redisson single-lock extension: the old implementation checked owner state locally and renewed the key TTL in a separate Redis command.

## Decision

Use a shared internal helper that performs owner verification and TTL renewal in one Redis Lua script. The helper reflects Redisson's own `getLockName(long)` owner field and `getRawName()` raw Redis key name from the resolved Redisson 4.4.0 artifact.

## Test Guard

Keep MockK doubles as class-level fields and reset them in `@BeforeEach` with `clearMocks(...)`. Do not introduce method-local `mockk(...)` or `spyk(...)` in new bluetape4k tests unless the review evidence explicitly justifies why the object cannot be a normal fixture or real test object.

## Outcome

The sync and suspend delegates now share the same owner-atomic extend path. Regression coverage verifies that stale-owner results map to `WrongThread` or `NotHeld`, and full `leader-redis-redisson` tests pass serially.

## Verification

- `RedissonOwnerAtomicExtendDelegateTest`: `BUILD SUCCESSFUL in 13s`
- Redisson extend contract tests: `BUILD SUCCESSFUL in 4s`
- `:bluetape4k-leader-redis-redisson:test --no-parallel`: `BUILD SUCCESSFUL in 19s`
- `git diff --check`: pass
