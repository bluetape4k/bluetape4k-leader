# 6-Tier Code Review — Issue #77 backend minLeaseTime

## Tier 1. Correctness

- `@LeaderElection` / `@LeaderGroupElection` restore `minLeaseTime = "PT0S"` and AOP maps it into core options.
- Core option constructors still enforce `minLeaseTime >= 0` and `minLeaseTime <= leaseTime`.
- Token/slot backends release by either normal delete/remove/unlock or by retaining the existing token with the remaining minimum lease TTL.
- Redis/Lettuce single lock uses Lua to atomically choose `DEL` or `PEXPIRE`.
- Mongo single/group slot locks update `expireAt` instead of deleting when minimum lease remains.
- Hazelcast single/group slot locks re-set the same token with a shortened TTL when minimum lease remains.
- Exposed JDBC/R2DBC single/group slot locks update `lockedUntil` instead of deleting when minimum lease remains.
- Redisson single lock updates the Redis lock key TTL with `RKeys.expire(Duration, key)` and skips unlock while minimum lease remains.

## Tier 2. API and Compatibility

- `minLeaseTime` is a new annotation property with a default, so existing annotation usages remain source compatible.
- `DurationParser.parse` behavior is unchanged for wait/lease durations; zero remains invalid there.
- `parseNonNegativeOrDefault` is only used for minimum lease parsing.
- Public helper `remainingMinLeaseTime` is exposed from `leader-core` so backend modules do not duplicate time arithmetic.

## Tier 3. Concurrency and Cancellation

- Suspend release paths keep their existing `NonCancellable` cleanup wrappers.
- Backend TTL delegation avoids blocking caller threads/coroutines after fast completion.
- Redisson `RLock` remains reentrant for the same thread. Cross-thread/node contenders are covered by the Redis TTL test.
- Hazelcast tests use >=1s TTL because Hazelcast map-entry TTL granularity is not reliable for sub-second assertions.

## Tier 4. Backend Coverage

- Covered with new fast-return tests: Lettuce single, Redisson single, Mongo single, Hazelcast single, Hazelcast group.
- Covered by existing regression suites after release-path changes: Exposed JDBC locks/groups, Exposed R2DBC locks/groups/electors.
- Local minLeaseTime behavior was implemented in #38 and remains stacked below this PR.
- Known gap: Lettuce/Redisson group implementations are counter/semaphore based, not per-slot token based. Exact `minLeaseTime` TTL delegation for one acquired Redis group permit needs a slot-token redesign.

## Tier 5. Tests and Coverage

- Focused annotation tests validate `minLeaseTime` defaults and custom values.
- AOP tests capture factory options and verify single/group `minLeaseTime` mapping.
- Parser/cache tests cover zero minimum lease and cache-key separation.
- Backend tests verify immediate reacquisition fails before minimum lease and succeeds after expiry.
- `leader-core` coverage remains above the 80% target through the stacked #38 branch.

## Tier 6. Documentation and Operations

- Root and module README files now describe `minLeaseTime` as lockAtLeastFor-style lease retention.
- Mongo and Hazelcast backend READMEs document storage TTL retention behavior.
- Spring Boot README examples show annotation `minLeaseTime`.
- PR should be draft/stacked on #149 until #38 is reviewed/merged.

## Residual Risks

- Redis group exact per-permit TTL is intentionally not solved in this patch because the current counter/semaphore model has no token per acquired permit.
- Redisson single-lock TTL delegation depends on Redisson's current key naming and `RKeys.expire` behavior; covered by integration test against RedisServer.
- Exposed minLeaseTime has existing regression coverage but no new explicit minLeaseTime test in this patch.
