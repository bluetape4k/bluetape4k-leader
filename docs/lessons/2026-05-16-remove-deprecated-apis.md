# Lesson: Remove all @Deprecated APIs before 0.1.0

**Date**: 2026-05-16
**Issue**: #264
**PR**: TBD

## Root Cause

10 APIs were deprecated during development with migration paths documented.
Publishing them in 0.1.0 would create a cluttered public API surface and
require maintaining backward compatibility from day one.

## Removed Items

| Item | Type | Action |
|------|------|--------|
| `LeaderLease.leaderId` | deprecated field | Removed; callers use `auditLeaderId` |
| `LeaderLeaseAutoExtender.start(Boolean lambda)` | deprecated overload | Removed; callers use `ExtendDelegate` form |
| `HistoryStatus.kt` typealias | deprecated file | Removed; callers use `LeaderHistoryStatus` |
| `RetryStrategy.kt` typealias | deprecated file | Removed (zero callers) |
| `ExposedJdbcGroupLock.extend()` | deprecated method | Removed; no production callers |
| `ExposedJdbcLock.extend()` | deprecated method | Removed; no production callers |
| `MongoLock.extend()` | deprecated method | Removed; no callers |
| `MongoSuspendLock.extend()` | deprecated method | Removed; no callers |
| `LettuceSemaphore` class | deprecated entire class | Removed + test file deleted |
| `LettuceSuspendSemaphore` class | deprecated entire class | Removed + test file deleted |

## Migration Notes

- `LettuceSemaphore` → use `LettuceLeaderGroupElector` (slot-token TTL model)
- `LettuceSuspendSemaphore` → use `LettuceSuspendLeaderGroupElector`
- `LeaderLease.leaderId` → use `LeaderLease.auditLeaderId`
- `HistoryStatus` → use `LeaderHistoryStatus`

## Key Decisions

- Tests that only tested deprecated code were deleted (not just disabled)
- Tests that used deprecated APIs were migrated to the new API
- 3 test files were updated, 1 test file (LettuceSemaphore test) was deleted

## Verification

- `./gradlew assemble` → BUILD SUCCESSFUL (76 tasks)
- `./gradlew :leader-core:test :leader-exposed-core:test` → BUILD SUCCESSFUL

## Future Guidance

Before adding `@Deprecated` to any public API:
1. Confirm the replacement is production-ready
2. Set explicit removal milestone in the deprecation message
3. Remove at the milestone — don't let deprecated APIs accumulate across releases
