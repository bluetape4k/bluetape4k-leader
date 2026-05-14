# Lettuce Coverage Lift

## Context

Issue #236 raised `leader-redis-lettuce` line coverage above 80%.

Baseline Kover line coverage was 66.93%. The first coverage run exposed an existing race in `LettuceCandidateRegistry.listCandidates`: Redis keys can expire between `SCAN` and `MGET`, and Lettuce `KeyValue.value` throws when the returned value is absent.

## Decision

Treat an absent `MGET` value as a candidate that expired between lookup and read. This matches the existing candidate TTL semantics and avoids restoring or failing on stale keys.

Coverage was raised by testing low-level Redis primitives directly:

- `LettuceLock` sync, async, and suspend acquire/extend/unlock/timeout contracts.
- Deprecated `LettuceSemaphore` sync, async, and suspend permit contracts.

## Verification

- `./gradlew :leader-redis-lettuce:test --console=plain`
  - 204 tests passing.
- `./gradlew :leader-redis-lettuce:koverXmlReportJvm :leader-redis-lettuce:koverLogJvm --console=plain`
  - line coverage: 84.6002%.
  - parsed XML: `TOTAL_LINE 857/1013 84.60%`.

## Lesson

For Redis `SCAN` followed by value reads, always handle key disappearance between the two operations. Do not call Lettuce `KeyValue.value` unless `hasValue()` is true.

When coverage gaps are concentrated in low-level infrastructure classes, focused contract tests can raise coverage more safely than adding broad public API scenarios.
