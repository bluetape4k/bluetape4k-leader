# Projects 1.9.2 BOM handoff

## Context

`bluetape4k-projects` 1.9.2 was released and `bluetape4k-bom:1.9.2` is visible
from Maven Central.

## Decision

Use the stable `bluetape4k-bom` 1.9.2 line for this release-prep branch instead
of the matching projects snapshot. Keep the Exposed BOM reference on its current
line because this handoff only promotes the already released projects BOM.

## Outcome

The version catalog now resolves `io.github.bluetape4k:bluetape4k-bom` from the
stable 1.9.2 release while leaving this repository's own release line unchanged.

## Verification

- Maven Central HTTP 200 for `bluetape4k-bom:1.9.2`
- `./gradlew help --refresh-dependencies --no-daemon --no-configuration-cache --no-build-cache`
