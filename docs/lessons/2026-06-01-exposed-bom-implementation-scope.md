# Exposed BOM implementation scope

## Context

The `bluetape4k-dependencies 1.2.0` release train promotes
`bluetape4k-exposed-bom` to `1.10.0`. Leader only needs the bluetape4k Exposed
line for exposed module tests and alignment, not as a public API BOM.

## Decision

Import `bluetape4k-exposed-bom` with `implementation(platform(...))` in the
publishable Exposed leader modules, and keep the test helper aliases aligned to
the same `1.10.0` line.

## Outcome

This keeps the local compile/test graph aligned with the release train while
avoiding an API-scoped bluetape4k Exposed BOM platform in published metadata.

## Verification

- Maven Central returned HTTP 200 for `bluetape4k-exposed-bom:1.10.0`,
  `bluetape4k-exposed-jdbc:1.10.0`, `bluetape4k-exposed-jdbc-tests:1.10.0`,
  and `bluetape4k-exposed-r2dbc-tests:1.10.0`.
- `./gradlew :bluetape4k-leader-exposed-core:build :bluetape4k-leader-exposed-jdbc:build :bluetape4k-leader-exposed-r2dbc:build --no-daemon --console=plain`
  passed.

## Future Guidance

If leader needs bluetape4k Exposed runtime artifacts later, add the concrete
artifact dependency deliberately; do not promote the BOM platform to `api`.
