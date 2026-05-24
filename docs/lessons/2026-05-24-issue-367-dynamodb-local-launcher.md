# Issue 367 DynamoDB Local Launcher

## Context

`leader-dynamodb` carried a private `DynamoDbLocalContainer` even after the
shared `DynamoDbLocalServer` fixture was added to `bluetape4k-testcontainers`.

## Decision

Consume the `1.9.2-SNAPSHOT` bluetape4k catalog and use
`DynamoDbLocalServer.Launcher.dynamoDb` from the integration test base.

## Outcome

The leader repository no longer duplicates the DynamoDB Local image, port, and
command wiring. Test lifecycle now follows the shared launcher pattern, where
the singleton container is registered in `ShutdownQueue` and client resources
remain owned by the leader tests.

The temporary `1.9.2-SNAPSHOT` catalog is acceptable for this branch because the
root build already sets `resolutionStrategy.cacheChangingModulesFor(0,
TimeUnit.SECONDS)`. No matching `bluetape4k-exposed 1.9.2-SNAPSHOT` existed at
implementation time, so exposed-module binary compatibility was checked by
compiling the exposed leader test sources with the current catalog pair.
`DynamoDbLocalServer` still pins `amazon/dynamodb-local:2.6.1`, matching the
private container it replaces.

## Verification

- `./gradlew :bluetape4k-leader-dynamodb:compileTestKotlin --refresh-dependencies --no-configuration-cache`
- `./gradlew :bluetape4k-leader-dynamodb:test --tests 'io.bluetape4k.leader.dynamodb.DynamoDbLeaderElectorIntegrationTest' --no-configuration-cache`
- `./gradlew :bluetape4k-leader-exposed-core:compileTestKotlin :bluetape4k-leader-exposed-jdbc:compileTestKotlin :bluetape4k-leader-exposed-r2dbc:compileTestKotlin --no-configuration-cache`

## Future Guard

Before replacing private test containers with shared launchers, verify the
snapshot or release artifact contains the new helper class. Snapshot metadata can
exist before the latest develop merge has been published.

Before cutting the next leader release, re-pin `bluetape4k` from
`1.9.2-SNAPSHOT` to the corresponding GA catalog and align `bluetape4k-exposed`
when its matching version exists.
