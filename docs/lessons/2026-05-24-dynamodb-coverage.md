# DynamoDB Leader Test Coverage

Context: Issue #366 identified missing DynamoDB coverage for async leader APIs,
watchdog lease extension, and suspend group cancellation cleanup.

Decision: Add focused integration tests for async execution, explicit
`autoExtend = true` watchdog behavior, and cancellation cleanup. Replace raw
executor-based contention checks with `MultithreadingTester` for blocking group
and single-leader contention.

Outcome: DynamoDB leader tests now cover the previously missing behavior and
avoid direct `Executors` / `CountDownLatch` usage in the touched contention
tests.

Verification: `./gradlew :bluetape4k-leader-dynamodb:test --tests
'io.bluetape4k.leader.dynamodb.DynamoDbLeaderElectorIntegrationTest' --tests
'io.bluetape4k.leader.dynamodb.DynamoDbLeaderGroupElectorIntegrationTest'
--tests
'io.bluetape4k.leader.dynamodb.DynamoDbSuspendLeaderGroupElectorIntegrationTest'`;
`git diff --check`.

Future guard: Watchdog tests must enable `autoExtend`; the default options keep
auto-extension disabled.
