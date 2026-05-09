# Backend minLeaseTime implementation plan

## Phase 1. AOP option restoration

- Add `minLeaseTime: String = "PT0S"` to both annotations.
- Parse `minLeaseTime` with a non-negative duration parser so `PT0S` is valid while negative values still fail.
- Include it in `LeaderElectionOptions` / `LeaderGroupElectionOptions`.
- Update annotation, aspect, validator, cache-key tests.

## Phase 2. Shared release helpers

- Reuse the #38 remaining-duration helper from leader-core.
- Make the helper visible to backend modules without exposing broad mutability.
- Add lock-level release methods that either unlock now or retain the backend TTL for the remaining minimum lease.

## Phase 3. Backend implementation

- Lettuce single lock: Lua `DEL` vs `PEXPIRE` based on remaining min lease.
- Mongo single/group slot locks: `deleteOne` vs `updateOne(expireAt = now + remaining)`.
- Hazelcast single/group slot locks: `remove` vs re-set the current token with the remaining TTL.
- Exposed JDBC/R2DBC single/group slot locks: `deleteWhere` vs `update lockedUntil`.
- Redisson single lock: key TTL update vs normal unlock.

## Phase 4. Tests and docs

- Add backend-focused fast-completion tests where the repo already has infrastructure coverage.
- Add annotation mapping tests for single and group AOP.
- Update README language around backend TTL delegation.
- Run affected module tests, compile, Kover where practical.
- Run 6-Tier review and create a stacked draft PR based on `feat/38-min-lease-time`.
