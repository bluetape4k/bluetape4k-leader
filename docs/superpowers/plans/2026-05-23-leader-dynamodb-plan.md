# leader-dynamodb Implementation Plan

Issue: https://github.com/bluetape4k/bluetape4k-leader/issues/228

## Decision

Implement the full #228 backend contract in one PR. The module introduces a new
public backend surface, so splitting only the scaffold would leave the Epic in a
non-adoptable state and defer the hardest contract risks.

## Tasks

1. Gradle/module wiring
   - Add `bluetape4k-leader-dynamodb` include and project directory.
   - Add BOM constraint.
   - Add AWS SDK v2 BOM/version and `aws2-dynamodb` dependency alias.
   - Add module `build.gradle.kts`.
   - Add module-local `src/test/resources/junit-platform.properties` with the
     repo's per-class/non-parallel Testcontainers-safe defaults and
     `logback-test.xml`.
   - Verify AWS SDK v2 version against the current `bluetape4k-aws`/
     `bluetape4k-dependencies` source of truth before final commit.

2. DynamoDB lock core
   - Implement key naming, lock-name validation, item mapping, monotonic
     deadline, and sync/async lock client.
   - Implement conditional acquire/release/extend and state reads.
   - Map `ConditionalCheckFailedException` to contention/NotHeld, not backend
     failure.
   - Reconcile conditional failure after retry with `ConsistentRead=true`
     `GetItem` and owner-id comparison.
   - Use the server-stored `leaseExpiry` from reconciliation when constructing
     lock state/handles after an uncertain write outcome.
   - Propagate throttling, auth, request-limit, internal-server,
     transaction-conflict, and client configuration errors.
   - Apply `clockSkewTolerance` to takeover checks and validate
     `leaseTime > 2 * clockSkewTolerance`.
   - Keep attribute names `leaseExpiry` and `ttl`, but codify constants and
     tests for millis-vs-seconds units.
   - Generate `ownerId` from a random UUID per acquire attempt.
   - Implement `ttlEpochSeconds = ceil((leaseExpiryMillis + ttlPaddingMillis) / 1000)`.
   - Include `attribute_not_exists(leaseExpiry)` in the acquire condition so a
     malformed row without expiry can be recovered.
   - Implement full-jitter backoff bounded by `retryDelay` for conditional
     failure retries.
   - Use delayed scheduling for async retry waits; do not block executor threads
     with `Thread.sleep`.

3. Electors and factories
   - Implement `DynamoDbLeaderElector` and `DynamoDbSuspendLeaderElector`.
   - Implement slot-based `DynamoDbLeaderGroupElector` and
     `DynamoDbSuspendLeaderGroupElector`.
   - Add factory classes and extension functions.
   - Push `LeaderLockHandle.real` into sync/suspend AOP scopes with shared
     extend delegates.
   - Implement suspend acquire with cancellation cleanup that does not rely on
     `CompletableFuture.await()` cancelling the underlying DynamoDB HTTP call.
     Cancellation cleanup must attach completion callbacks that fire on both
     success and failure and release by `ownerId` after a late successful
     acquire.
   - Implement group acquisition as randomized-start linear conditional probing;
     do not scan or pre-read in the acquisition path.

4. Spring Boot wiring
   - Add DynamoDB properties under `LeaderProperties`.
   - Add conditional beans when `DynamoDbClient` / `DynamoDbAsyncClient` are on
     the classpath and present as beans.
   - Require explicit backend selection (`bluetape4k.leader.backend=dynamodb`)
     so mere client presence does not activate DynamoDB over another backend.
   - Apply the same `@ConditionalOnProperty` selector to every DynamoDB Spring
     Boot phase, including client-dependent beans, elector beans, and any AOP
     integration wiring.
   - Document that production table provisioning remains caller/operator
     responsibility; auto-configuration does not create tables.
   - Add auto-configuration tests using `ApplicationContextRunner`.

5. DynamoDB Local tests
   - Add module-local `DynamoDbServer.Launcher` fixture.
   - Create the lock table per test class.
   - Cover contention, logical expiry takeover, group slot limits, async,
     suspend, and LockExtender behavior.
   - Cover suspend cancellation propagation and best-effort cleanup with a
     cancellation-specific test.
   - Cover clock-skew tolerance, conditional retry reconciliation, stale release
     idempotency, and `leaseExpiry`/`ttl` unit correctness.
   - Cover `minLeaseTime` release branching: `UpdateItem` when remaining time
     exists and `DeleteItem` when no minimum lease remains.
   - Cover group slot logical-expiry takeover separately from single-lock
     takeover.
   - Cover single-lock watchdog auto-renewal through the shared extend delegate.
   - Cover infrastructure exception propagation so throttling, auth,
     request-limit, internal-server, transaction-conflict, and client
     configuration failures do not silently return `null`.
   - Document real AWS DynamoDB smoke testing as manual/out-of-CI because this
     repository does not provide AWS credentials to CI.

6. Workflow updates
   - Add CI path filter, test job, and aggregator needs.
   - Add nightly test job and summary needs.
   - Run `actionlint` for workflow changes.
   - Update `README.md` and `README.ko.md` backend/module tables.
   - Add README provisioning note for `lockName` partition key and `ttl` TTL
     attribute, plus a warning that DynamoDB Local does not physically enforce
     TTL deletion timing.

7. Verification and review gates
   - Run `./gradlew projects`.
   - Run targeted `:bluetape4k-leader-dynamodb:test`.
   - Run affected Spring Boot tests.
   - Set/verify the new module Kover threshold according to the repo's
     Testcontainers-backed module convention.
   - Run `actionlint`.
   - Run 6-Tier local review and Claude Code CLI 6-Tier review with P0/P1 = 0.
   - Add lesson document.
   - Commit with Lore protocol.
   - Open PR and update #228 progress.

## 6-Tier Review Focus

1. Security: no credentials, no owner spoofing path, conditional owner guards.
2. Ops/SRE: endpoint/client ownership, cleanup behavior, failure logging, CI
   Testcontainers stability.
3. Structural impact: module boundaries, BOM/public API, Spring Boot conditionals.
4. Kotlin/code quality: cancellation handling, public KDoc, existing patterns.
5. Tests/types/silent failure: skip-on-contention, stale extend, null-result
   ambiguity, logical TTL takeover.
6. Performance/stability: hot-key retry jitter, scan-only state methods,
   async retry without blocking executor threads.

## Stop Condition

Stop only after PR creation, local targeted validation, 6-Tier dual review
artifacts, and #228 progress update. Do not merge until GitHub CI is successful.
