# Backend minLeaseTime TTL delegation design

## Goal

Issue #77 completes the ShedLock-style `lockAtLeastFor` path after #38 added the core `minLeaseTime` option and local reference behavior.

The important backend rule is: when action finishes before `minLeaseTime`, the caller must return immediately and the storage backend must keep the lock unavailable until the remaining minimum lease expires. Backend adapters must not block application threads just to satisfy `minLeaseTime`.

## Scope

- Restore `minLeaseTime` on `@LeaderElection` and `@LeaderGroupElection`.
- Map annotation durations into `LeaderElectionOptions` and `LeaderGroupElectionOptions`.
- Keep option validation fail-fast through the core option constructors (`minLeaseTime >= 0`, `minLeaseTime <= leaseTime`).
- Add backend release paths that retain a held token/slot until the remaining minimum lease expires.
- Add focused integration/unit tests for fast-return behavior.

## Backend strategy

### Token/slot backends

MongoDB, Hazelcast, Exposed JDBC, Exposed R2DBC, and Lettuce single locks can update the existing lock row/key TTL instead of deleting/releasing it:

- compute `remaining = acquiredAt + minLeaseTime - now`
- if `remaining > 0`, keep the current token and update the backend expiry to `now + remaining`
- else perform the normal unlock/delete

This preserves caller fast return and node-death behavior.

### Redisson single lock

`RLock` does not expose `expire`, but Redisson `RKeys.expire(Duration, key)` can update the Redis lock key TTL by name. If the current lock owner is still valid and `remaining > 0`, update the key TTL and skip `unlock`; otherwise use the normal Redisson unlock path. Same-thread reentrancy remains a Redisson `RLock` property; cross-thread/node contenders are blocked until the key expires.

### Redis semaphore-style groups

The existing Lettuce/Redisson group implementations are counter/semaphore based rather than per-slot token rows. They cannot safely attach a different TTL to one acquired permit without changing the backend model. For this PR, minLeaseTime is implemented where slot tokens already exist. Semaphore group redesign remains a follow-up if exact Redis group TTL delegation is required.

## AOP contract

`minLeaseTime` uses the same duration parser as `waitTime` and `leaseTime`:

- empty string means zero duration
- ISO-8601 (`PT10S`) and simple values (`10s`, `500ms`) are accepted
- invalid or greater-than-lease values fail during metadata resolution

The Aspect must not sleep. It only passes `minLeaseTime` into the selected factory options.

## Risks

- Backend clocks determine expiry. Clock skew remains a distributed locking concern and should be documented with `leaseTime`.
- Redisson key TTL manipulation depends on the lock key name and Redisson's Redis data layout. Tests should verify behavior against a real Redis server.
- Redis group semaphore exact semantics need a slot-token redesign, not a small release-path patch.
