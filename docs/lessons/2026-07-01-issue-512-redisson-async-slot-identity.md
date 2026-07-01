# Lessons Learned - Issue 512 Redisson Async Slot Identity (2026-07-01)

## Context

Issue #512 found that Redisson async `LeaderSlot` APIs inherited the default
bridge path. Blocking and suspend APIs preserved `slot.leaderId`, but async
single/group result APIs could return `LeaderRunResult.Elected(..., leaderId =
null)` and emit bridge warnings.

## Decision

Mirror the Lettuce async slot identity fix in Redisson single/group electors by
overriding both slot variants:

- `runAsyncIfLeader(slot, ...)`
- `runAsyncIfLeaderResult(slot, ...)`

Single-lock async execution pushes a real `LeaderLockHandle` while creating the
async action. Group async execution also writes `slot.leaderId` into the
Redisson audit map for the acquired permit and removes that entry during async
cleanup.

## Outcome

The failing async contract tests reproduced the issue first, then passed after
the Redisson implementation change.

Validation evidence:

- `./gradlew :bluetape4k-leader-redis-redisson:test --tests '*RedissonAsyncLeader*LeaderIdContractTest' --no-parallel`
- `./gradlew :bluetape4k-leader-redis-redisson:test --no-parallel`
- Test XML summary: 198 tests, 0 failures, 0 errors, 0 skipped.

## Future Guard

When a backend implements slot-aware blocking or suspend APIs, verify async
slot APIs separately. Default bridge methods deliberately warn and drop
`leaderId`; backend modules must override both async slot variants to preserve
audit identity.

Do not broaden Redisson async slot identity fixes into release-completion
ordering changes unless the active issue explicitly covers that behavior.
