# Issue #423 DynamoDB Scheduled Export Example Plan

Spec:
`docs/superpowers/specs/2026-06-05-issue-423-dynamodb-export-example-design.md`

## 1. Module Registration

- Add `examples:dynamodb-export` to `settings.gradle.kts`.
- Add the example to repo-local `AGENTS.md` example list.
- Add paths-filter output and filter in `.github/workflows/ci.yml`.
- Add `test-examples-dynamodb-export` job and include it in `coverage-report`
  and `ci-status` `needs`.
- Add matrix entry to `.github/workflows/examples.yml`.

## 2. Example Implementation

- Add `examples/dynamodb-export/build.gradle.kts`.
- Add `DynamoDbExportStatus`, `DynamoDbExportReport`, and
  `DynamoDbExportRecord` as serializable value types.
- Add `DynamoDbExportTable` for example export-table DDL, write, and read
  helpers.
- Add `DynamoDbScheduledExportRunner` using
  `DynamoDbSuspendLeaderElector.runIfLeader`.
- Add `DynamoDbExportDemo` using `DynamoDbLocalServer.Launcher.dynamoDb`.
- Keep blocking DynamoDB SDK calls wrapped in `withContext(Dispatchers.IO)` when
  called from suspend functions.
- Rethrow `CancellationException` before broad exception handling.

## 3. Tests

- Add `AbstractDynamoDbExportTest` with shared DynamoDB Local clients, lock table,
  and export table.
- Add a single-run test proving one node exports.
- Add a contention test using one active leader and one contender; assert
  `EXPORTED`, `SKIPPED`, and exactly one export record for the shared batch.
- Add a sequential reacquire test proving the next node can export after release.
- Add validation tests for blank node id, lock name, batch id, and summary.
- Add `junit-platform.properties` and `logback-test.xml`.

## 4. Documentation

- Add `examples/dynamodb-export/README.md` and `README.ko.md`.
- Update `examples/README.md` and `examples/README.ko.md`.
- Update root `README.md` and `README.ko.md`.
- Mention DynamoDB Local/Testcontainers, lock table shape, export table shape,
  and TTL caveat.

## 5. Verification And Review

- Run targeted compile and test for `:examples:dynamodb-export`.
- Run `./gradlew projects` to verify registration.
- Run `actionlint` for touched workflow files.
- Run `git diff --check`.
- Run local 7-Tier diff review and fix all P0/P1 findings.
- Add a concise `docs/lessons/2026-06-05-issue-423-dynamodb-export-example.md`.
- Commit with Lore trailers, push, create PR with verified body ending in
  `## DoD Status`, run PR review gate, then wait for CI.

## Risks

- DynamoDB Local and Testcontainers make local/CI tests slower; keep the module
  test focused and serial.
- DynamoDB TTL is eventually applied and not part of correctness; README and
  tests must rely on logical lease behavior instead.
- Workflow registration has several duplicated lists; verify all lists before
  PR creation.
