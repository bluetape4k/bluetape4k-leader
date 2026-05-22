# Issue #228 - DynamoDB Leader Backend

## Context

Issue #228 added the `leader-dynamodb` backend with blocking, async facade,
coroutine, virtual-thread, group-election, Spring Boot, and CI/nightly coverage.
The backend uses caller-owned DynamoDB tables with `lockName` as the partition
key and `leaseExpiry` as the logical correctness boundary.

## Decision or Finding

- Treat DynamoDB `ttl` as cleanup metadata only. All correctness checks must use
  conditional writes against `leaseExpiry`.
- Use unique `ownerId` values as fencing tokens and store `auditLeaderId`
  separately so slot-based `LeaderRunResult` and state APIs keep caller-visible
  leader identity.
- Group state enumeration must paginate DynamoDB `Scan` responses. A first-page
  scan silently truncates state once the table response crosses DynamoDB's page
  boundary.
- Suspend acquisition should not cancel the AWS SDK future directly. If a
  coroutine is cancelled while acquisition is still in flight, release any late
  successful acquisition instead.

## Outcome

The implementation now includes paginated group state scans, cancellation-aware
internal async acquisition with late-release protection, watchdog-close
isolation before release, and Spring Boot/AOP factory wiring for DynamoDB.

## Verification

- `./gradlew :bluetape4k-leader-dynamodb:test --no-daemon --console=plain`
  passed 18 tests.
- `./gradlew :bluetape4k-leader-spring-boot:test --tests 'io.bluetape4k.leader.spring.BackendConditionalTest' --tests 'io.bluetape4k.leader.spring.aop.autoconfigure.DynamoDbAopFactoryAutoConfigurationTest' --no-daemon --console=plain`
  passed 18 tests.
- `./gradlew build -x test -x k8sTest --no-daemon --console=plain` passed.
- `actionlint .github/workflows/ci.yml .github/workflows/nightly-tests.yml`
  passed.
- `git diff --check` passed.
- Claude 6-Tier review artifact
  `.omx/artifacts/claude-dynamodb-code-review-compact-rerun2-20260523023923.md`
  ended with `P0=0 P1=0`.

## Future Guidance

If DynamoDB group state becomes a hot operational path, avoid full-table Scan
with a filter expression. Add a queryable `groupPrefix` attribute plus a GSI, or
use a table layout that can query group slots directly.
