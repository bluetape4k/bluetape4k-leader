# leader-dynamodb Design Spec

## Scope

Issue: https://github.com/bluetape4k/bluetape4k-leader/issues/228

Add a publishable `bluetape4k-leader-dynamodb` backend that implements the
existing leader-election contracts with AWS SDK for Java 2.x DynamoDB clients.
The module must cover single-leader and slot-based group election for blocking,
async `CompletableFuture`, and coroutine suspend execution.

## External Evidence

- AWS SDK for Java 2.x DynamoDB low-level clients expose `DynamoDbClient` and
  `DynamoDbAsyncClient`; the low-level client is appropriate because lock rows
  are dynamic infrastructure records, not domain documents.
- DynamoDB condition expressions support atomic conditional put, delete, and
  update operations; failed conditions map to lock contention or stale-owner
  outcomes.
- DynamoDB TTL values must be numeric Unix epoch seconds. TTL deletion is
  eventual, so lock takeover must be based on `leaseExpiry < now`, not physical
  TTL deletion.
- DynamoDB Local is available as a Docker image and can be used by
  Testcontainers with endpoint override and static test credentials.

## Public API

Package: `io.bluetape4k.leader.dynamodb`

New API:

- `DynamoDbLeaderElectionOptions`
- `DynamoDbLeaderGroupElectionOptions`
- `DynamoDbLeaderElector`
- `DynamoDbSuspendLeaderElector`
- `DynamoDbLeaderGroupElector`
- `DynamoDbSuspendLeaderGroupElector`
- factories matching existing backend modules
- extension helpers on `DynamoDbClient` and `DynamoDbAsyncClient`

Option fields:

- `leaderOptions` / `leaderGroupOptions`
- `tableName`: default `bluetape4k_leader_locks`
- `keyPrefix`: default `leader`
- `retryDelay`: default `50.milliseconds`
- `ttlPadding`: default `60.seconds`
- `clockSkewTolerance`: default `5.seconds`

`ttlPadding` keeps DynamoDB's eventual physical TTL cleanup separate from
logical lease expiry. Runtime correctness is determined by `leaseExpiry`.
`clockSkewTolerance` is subtracted from takeover checks so a fast client clock
does not acquire a lock that may still be valid on the current owner. The
effective takeover condition is `leaseExpiry < now - clockSkewTolerance`.
`leaseTime` must be greater than `2 * clockSkewTolerance`; validation failures
must name `leaseTime`, `clockSkewTolerance`, and the required relationship.

## Table Model

Single table, partition key only:

- `lockName` (S, partition key)
- `ownerId` (S)
- `leaseExpiry` (N, epoch millis; implementation constant documents this as
  `leaseExpiryMillis`)
- `ttl` (N, epoch seconds; implementation constant documents this as
  `ttlEpochSeconds`)

TTL formula:

```text
leaseExpiryMillis = nowMillis + leaseTimeMillis
ttlEpochSeconds = ceil((leaseExpiryMillis + ttlPaddingMillis) / 1000)
```

`ownerId` must be generated per acquisition attempt with a random UUID string.
It must not be derived from hostname, process id, thread id, or user input.
One acquire call generates one `ownerId` and reuses it across all internal
conditional-write retries for that call.

Derived keys:

- Single lock: `{keyPrefix}/single/{lockName}`
- Group lock slot: `{keyPrefix}/group/{lockName}#slot-{index}`

`lockName` values are validated with the core `validateLockName`; raw lock names
must not include the reserved `#slot-` suffix.

## Lock Operations

Acquire:

- `PutItem`
- Condition:
  `attribute_not_exists(lockName) OR attribute_not_exists(leaseExpiry) OR leaseExpiry < :safeNow`,
  where `safeNow = now - clockSkewTolerance` in epoch millis
- Set `ownerId`, `leaseExpiry`, `ttl`
- `ConditionalCheckFailedException` means contention and must return `false`.
- After a conditional failure, perform a `ConsistentRead=true` `GetItem`; if the
  row already has this attempt's `ownerId`, treat the acquisition as successful.
  This reconciles client-side timeout/retry cases where DynamoDB applied the
  first write and a later retry failed its own condition.
- When reconciliation succeeds, the lock handle/state must use the server-stored
  `leaseExpiry`, not a locally recomputed deadline.
- Throttling, request-limit, internal-server, transaction-conflict, auth, and
  client configuration errors are infrastructure failures and must propagate;
  they are not skip-on-contention outcomes.
- Retry waits must be non-blocking in async/suspend paths. Use SDK async
  futures and delayed scheduling rather than `Thread.sleep` on executor threads.

Release:

- `DeleteItem`
- Condition: `ownerId = :owner`
- Conditional failure is logged as stale release and must not throw from cleanup.
- If `minLeaseTime` remains, use `UpdateItem` with owner guard instead of delete
  to shorten/retain the row until the minimum hold period ends.

Extend:

- `UpdateItem`
- Condition: `ownerId = :owner AND leaseExpiry > :now`
- Set new `leaseExpiry` and `ttl`
- Conditional failure returns `ExtendOutcome.NotHeld`.

State:

- `GetItem` for single lock
- `Scan` with `begins_with(lockName, :prefix)` for group slots. This is only for
  state/diagnostics; lock acquisition remains per-key conditional writes.

Group acquisition:

- Do not scan or read to pick a free slot.
- Start from a randomized slot and linearly probe all `maxLeaders` slots.
- Each slot attempt is an independent conditional `PutItem`.
- `ConditionalCheckFailedException` moves to the next slot.
- Exhaustion returns `null`.

## Contract Requirements

- `runIfLeader()` returns `null` on contention and never throws for normal
  conditional-write contention.
- Action exceptions propagate.
- `CancellationException` propagates from suspend paths after best-effort
  `NonCancellable` cleanup.
- Suspend mutating calls must not use `CompletableFuture.await()` for acquire,
  because cancellation can cancel the future object without aborting the
  underlying HTTP call. Instead, attach cancellation cleanup that releases by the
  pre-generated `ownerId` when the acquire future eventually succeeds. Use a
  completion callback that fires for both success and failure; cancellation
  cleanup must not depend on the cancelled coroutine resuming normally.
- `LockAssert` / `LockExtender` work through `LeaderLockHandle.real`.
- Explicit extend and watchdog share the same delegate instance.
- Group election allows at most `maxLeaders` concurrent actions and disables
  watchdog by default, matching existing group backend behavior.
- Crash recovery is logical: after `leaseExpiry` passes, another owner can
  acquire even if DynamoDB TTL has not physically deleted the old item.

## Build Integration

- Add module in `settings.gradle.kts`.
- Add BOM constraint.
- Add AWS SDK v2 BOM/version alias and DynamoDB dependency alias using the
  bluetape4k dependency source-of-truth version (`aws2 = 2.44.9`).
- Add CI and nightly workflow entries.
- Add Spring Boot conditional backend wiring when DynamoDB clients are present.
- Update `README.md` and `README.ko.md` backend/module tables so library users
  can discover the new backend.

Table provisioning:

- The runtime module and Spring Boot auto-configuration do not create production
  DynamoDB tables.
- Callers/operators must provision a table with partition key `lockName` (S)
  and TTL enabled on attribute `ttl`.
- Test fixtures may create tables for DynamoDB Local integration tests only.
- Spring Boot wiring must require explicit backend selection, e.g.
  `bluetape4k.leader.backend=dynamodb`, so a DynamoDB client bean does not
  silently override another configured backend.
- If Spring Boot wiring is split across client/elector/AOP integration phases,
  every phase must repeat the DynamoDB backend selector condition rather than
  relying on an upstream configuration class to gate it.

## Tests

Use DynamoDB Local through a module-local Testcontainers fixture:

- create table with `lockName` partition key
- enable TTL on `ttl` attribute where DynamoDB Local supports the operation
- configure sync and async clients with endpoint override, static credentials,
  and `us-east-1`

Required coverage:

- single `runIfLeader` elected path
- single contention returns `null`
- logical expiry allows takeover without waiting for physical TTL deletion
- group slot logical expiry allows takeover without waiting for physical TTL
  deletion
- clock-skew tolerance prevents premature takeover until `leaseExpiry` is older
  than `now - clockSkewTolerance`
- clock-skew tolerance allows takeover once `leaseExpiry < now - clockSkewTolerance`
- async path returns `null` on contention
- suspend elected path and contention
- group maxLeaders contention
- sync/suspend `LockExtender` delegates return success while lock is held
- suspend cancellation propagates and still performs best-effort cleanup in
  `NonCancellable`
- conditional retry reconciliation treats an already-written `ownerId` as
  acquired
- `leaseExpiry` millis and `ttl` seconds are written with correct units
- release uses `UpdateItem` when `minLeaseTime` remains and `DeleteItem` when it
  does not
- throttling, auth, request-limit, internal-server, transaction-conflict, and
  client configuration errors propagate instead of returning `null`
- Spring Boot conditional bean creation

## Acceptance Mapping

- All four elector interfaces: implemented by sync/suspend single and group
  electors, with async inherited implementations on sync interfaces.
- Skip-on-contention: covered by sync, async, and suspend tests.
- Crash recovery: covered by expired-row takeover test.
- DynamoDB Local integration: module test suite.
- bluetape4k-aws conventions: AWS SDK v2 clients are accepted directly, so
  applications can reuse their existing `bluetape4k-aws` client configuration.

Out of CI scope: real AWS DynamoDB nightly tests. This repository's CI does not
carry AWS credentials for managed-service integration tests. The PR will verify
against DynamoDB Local and source-compatible AWS SDK v2 APIs, and document the
manual real-service smoke command instead of adding credential-gated CI.

## Risks

- DynamoDB state scans are more expensive than key reads. This is acceptable for
  diagnostic state methods, but production scheduling should not poll them.
- TTL physical deletion is eventual. Correctness must never depend on deletion
  timing.
- Conditional write retries can amplify hot-key load. The module uses full
  jitter bounded by `retryDelay`.
- Client-clock skew can cause premature takeover if hosts are not time-synced.
  The backend applies `clockSkewTolerance`; callers must keep host time within
  that bound or increase lease durations/tolerance.
