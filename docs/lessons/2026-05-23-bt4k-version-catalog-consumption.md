# bt4k Version Catalog Consumption

## Context

`bluetape4k-leader` duplicated several shared dependency versions locally while
the ecosystem catalog already publishes those values.

## Decision

Import `io.github.bluetape4k:bluetape4k-version-catalog` as `bt4k` and resolve
shared dependency constraints via `bt4kVersion(alias)`.

## Outcome

The selected shared dependency aliases are now versionless in the local catalog.
Their versions are supplied by dependency management from the shared catalog.

## Verification

- `git diff --check`
- `./gradlew help --no-daemon --no-configuration-cache`
- `./gradlew compileKotlin --no-daemon --no-configuration-cache`

## Future Guidance

Avoid local pins for shared Redis, JDBC/R2DBC, logging, and cluster dependency
versions when `bt4k` already exposes them.
