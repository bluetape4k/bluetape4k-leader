# Issue #423 DynamoDB Scheduled Export Example Spec

## Scope

Issue: https://github.com/bluetape4k/bluetape4k-leader/issues/423

Add a runnable `examples/dynamodb-export` module that demonstrates a scheduled
export or billing-style job coordinated by the preview `leader-dynamodb`
backend. The example must run against DynamoDB Local through the existing
Testcontainers launcher and show that only the elected node writes an export
record for a shared schedule lock.

## Current Evidence

- `settings.gradle.kts` includes the existing examples but no DynamoDB example.
- `.github/workflows/ci.yml` has paths-filter outputs and per-example jobs for
  current examples only.
- `.github/workflows/examples.yml` has a matrix for current examples only.
- `leader-dynamodb` tests use `DynamoDbLocalServer.Launcher.dynamoDb`, caller
  owned tables with `lockName` as the hash key, and TTL on `ttl`.
- Existing example modules use `application`, `README.md`, `README.ko.md`,
  `junit-platform.properties`, and `logback-test.xml`.

## Design

### Module

- Add `examples/dynamodb-export`.
- Use `application` plugin with main class
  `io.bluetape4k.leader.examples.dynamodbexport.DynamoDbExportDemo`.
- Depend on `:bluetape4k-leader-dynamodb`, AWS SDK DynamoDB, coroutines,
  bluetape4k logging, and bluetape4k Testcontainers for the demo/test launcher.

### Example API

Implement a small suspend API:

- `DynamoDbScheduledExportRunner`
  - owns one node identity, one `DynamoDbSuspendLeaderElector`, and one export
    table name.
  - `runOnce(batchId, exportJob)` calls `runIfLeader(lockName)` and writes one
    export record only from the elected node.
  - returns a serializable `DynamoDbExportReport` containing node id, status,
    batch id, optional export id, and elapsed time.
- `DynamoDbExportStatus`
  - `EXPORTED` when this node held leadership and wrote the export record.
  - `SKIPPED` when another node held leadership.
- `DynamoDbExportTable`
  - creates the demo export table with hash key `exportId`.
  - writes export records with node id, batch id, created timestamp, and summary.
  - reads records for tests and demo output.

The runner is intentionally example-local. It must not add public API to
`leader-dynamodb`.

### DynamoDB Tables

The example uses two caller-owned tables:

- Leader lock table:
  - hash key: `lockName`
  - TTL attribute: `ttl`
  - same shape as `leader-dynamodb`.
- Export table:
  - hash key: `exportId`
  - ordinary demo data, not used for leadership correctness.

DynamoDB TTL is documented as cleanup metadata only. Correctness depends on
`leaseExpiry` and conditional writes in the lock table.

### Demo

`DynamoDbExportDemo` starts DynamoDB Local, creates the lock/export tables,
creates two runner nodes with the same lock name, and runs a contention scenario:

1. node-a starts an export and holds the leadership body briefly.
2. node-b attempts the same scheduled export while node-a holds the lock and
   skips.
3. after release, node-b can run the next export batch.

## Acceptance Criteria

- `examples/dynamodb-export` exists and can run with Docker/Testcontainers.
- Tests prove only one node executes a scheduled export for a shared leader id
  while the leader is active.
- Tests prove sequential reacquire after release.
- README locale set explains DynamoDB Local, table shape, logical TTL, and run
  commands.
- Root `README.md`, root `README.ko.md`, `examples/README.md`, and
  `examples/README.ko.md` list the new example.
- `settings.gradle.kts`, repo-local `AGENTS.md`, `ci.yml`, and `examples.yml`
  include the new example.

## Validation

- `./gradlew :examples:dynamodb-export:compileKotlin --no-daemon --no-configuration-cache`
- `./gradlew :examples:dynamodb-export:test --no-daemon --no-configuration-cache`
- `./gradlew projects --no-daemon --no-configuration-cache`
- `actionlint .github/workflows/ci.yml .github/workflows/examples.yml`
- `git diff --check`
- local 7-Tier review with `P0 = 0` and `P1 = 0`

## Out Of Scope

- Real AWS DynamoDB smoke tests.
- Publishing/BOM changes; examples are excluded by `path.startsWith(":examples:")`.
- New `leader-dynamodb` production APIs.
