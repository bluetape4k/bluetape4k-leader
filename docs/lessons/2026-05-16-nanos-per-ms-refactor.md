# Lesson: Replace 1_000_000L magic number with TimeUnit.NANOSECONDS.toMillis()

**Date**: 2026-05-16
**Issue**: #265
**PR**: TBD

## Root Cause

The magic number `1_000_000L` (nanoseconds-to-milliseconds divisor) appeared 21 times
across 7 files in 4 modules with no named constant, making the intent opaque and
creating a maintenance risk.

## Decision

Replaced `(System.nanoTime() - acquiredAtNanos) / 1_000_000L` with
`TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - acquiredAtNanos)` in all 7 files.

Chose `TimeUnit.NANOSECONDS.toMillis()` over a named constant (`NANOS_PER_MS`) because:
- Self-documenting — no need to look up what the constant means
- Standard JDK idiom (java.util.concurrent.TimeUnit)
- No new constant to share across modules
- Avoids cross-module visibility concerns (internal vs public)

Not changed: `RETRY_DELAY_NANOS` and `SPIN_DELAY_NANOS` definitions in
`LettuceLock.kt`, `LettuceSemaphore.kt`, `LettuceSlotTokenGroup.kt` — those
multiply by `1_000_000L` as part of a named constant definition (already readable).

## Files Changed

7 production files across 4 modules:
- `leader-exposed-jdbc`: `ExposedJdbcLeaderElector.kt` (4), `ExposedJdbcLeaderGroupElector.kt` (4)
- `leader-exposed-r2dbc`: `ExposedR2DbcSuspendLeaderElector.kt` (2)
- `leader-mongodb`: `MongoLeaderElector.kt` (4), `MongoSuspendLeaderElector.kt` (2)
- `leader-redis-lettuce`: `LettuceLeaderElector.kt` (4), `LettuceSuspendLeaderElector.kt` (2)

## Verification

- `rg "/ 1_000_000L" --glob "*.kt"` → 0 matches in production code
- `./gradlew :leader-exposed-jdbc:build :leader-exposed-r2dbc:build :leader-mongodb:build :leader-redis-lettuce:build -x test` → BUILD SUCCESSFUL

## Future Guidance

For any new duration-to-milliseconds conversion from `System.nanoTime()`:
use `TimeUnit.NANOSECONDS.toMillis(nanos)` — not `/ 1_000_000L`.
