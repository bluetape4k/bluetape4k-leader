# Lessons Learned - Issue 511 Lettuce Async Slot Identity (2026-07-01)

## Context

Issue #511 found that Lettuce async `LeaderSlot` APIs inherited the default
bridge path. Blocking and suspend APIs preserved `slot.leaderId`, but async
single/group result APIs could return `LeaderRunResult.Elected(..., leaderId =
null)` and emit bridge warnings.

## Decision

Add async slot identity contract fixtures in `leader-core` test fixtures and
make Lettuce single/group async electors override both slot variants:

- `runAsyncIfLeader(slot, ...)`
- `runAsyncIfLeaderResult(slot, ...)`

Group async acquire must pass `slot.leaderId` into `tryAcquireAsync` so Redis
slot metadata matches sync and suspend behavior.

## Outcome

The failing async contract tests reproduced the issue first, then passed after
the Lettuce implementation change.

Validation evidence:

- `./gradlew :bluetape4k-leader-redis-lettuce:test --tests '*LettuceAsyncLeader*LeaderIdContractTest' --no-parallel`
- `./gradlew :bluetape4k-leader-redis-lettuce:test --no-parallel`
- Test XML summary: 221 tests, 0 failures, 0 errors, 0 skipped.

## Future Guard

When a backend implements slot-aware blocking or suspend APIs, verify async
slot APIs separately. Default bridge methods deliberately warn and drop
`leaderId`; backend modules must override both async slot variants to preserve
audit identity.
