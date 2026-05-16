# Lesson: English KDoc for strategy/scorer public APIs

**Date**: 2026-05-16
**Issue**: #267
**PR**: #277

## Root Cause

Nine public classes and interfaces in the `leader-core` strategy/scorer subsystem had
Korean KDoc. The workspace CLAUDE.md requires English KDoc for new or meaningfully
changed public API; the Korean text was written before the English-first rule was adopted.

## Files Updated

| File | Change |
|------|--------|
| `strategy/CandidateScorer.kt` | Korean → English; added `## Behavior / Contract` and `## Example` |
| `strategy/ElectionStrategy.kt` | Korean → English; added `## Behavior / Contract`, `## Built-in strategies`, updated custom-strategy example |
| `strategy/scorers/IdleTimeScorer.kt` | Korean → English; added `## Behavior / Contract` and `## Example` |
| `strategy/scorers/RecentSuccessScorer.kt` | Korean → English; renamed section headers |
| `strategy/scorers/SuccessRateScorer.kt` | Korean → English |
| `strategy/scorers/WeightedScorer.kt` | Korean → English; added `## Behavior / Contract` |
| `strategy/strategies/FifoElectionStrategy.kt` | Korean → English; elimination reason strings also converted |
| `strategy/strategies/RandomElectionStrategy.kt` | Korean → English; elimination reason strings converted |
| `strategy/strategies/ScoredElectionStrategy.kt` | Korean → English; elimination reason strings converted |

Note: `ListeningLeaderElectors.kt` and `TenantScopedLeaderElectors.kt` already had
complete English KDoc and required no changes.

## Elimination Reason Strings

Elimination reason strings inside `ElectionResult` are part of the public audit trail
exposed to callers. They were also converted to English so the public `ElectionResult`
surface is consistent:

- `"등록 시각 늦음"` → `"registered later"`
- `"nodeId 사전순 뒤"` → `"nodeId lexicographically after winner"`
- `"랜덤 선출 탈락"` → `"not selected by random election"`
- `"점수 미달"` → `"score below winner"`
- `"점수 동점"` → `"tied score — ranked lower by registeredAt/nodeId"`

## KDoc Format Applied

Per CLAUDE.md for public classes:
1. One-line summary sentence.
2. `## Behavior / Contract` section listing invariants and edge cases.
3. `## Example` or `## Example / Built-in strategies` Kotlin code block.
4. `@property` / `@param` / `@return` tags where a parameter's semantics are non-obvious.

## Future Guidance

When adding a new `ElectionStrategy` or `CandidateScorer`:
1. Write English KDoc from the start.
2. Include `## Behavior / Contract` — determinism invariant is mandatory for `ElectionStrategy`.
3. Include an `## Example` showing typical usage.
4. Translate any user-visible string literals (elimination reasons, log messages) to English.
