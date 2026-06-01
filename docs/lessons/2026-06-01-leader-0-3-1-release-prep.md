# Leader 0.3.1 release prep

## Context

`bluetape4k-leader` 0.3.0 already consumed `bluetape4k-bom` 1.10.0, but its
Exposed alignment was still on the 1.9.2 line. The dependency train needs the
published leader line to consume the latest released `bluetape4k-exposed-bom`
without exposing that platform as API metadata.

## Decision

Publish `0.3.1` as a patch release from the current release-train alignment:
`bluetape4k-bom` 1.10.0, `bluetape4k-exposed-bom` 1.10.0, and
`implementation(platform(...))` in the publishable Exposed modules.

## Outcome

The patch release remains dependency-only and avoids pulling the broader 0.4.0
feature backlog into the stable release gate.

## Verification

- Maven Central returned HTTP 200 for the 0.3.0 leader artifacts and confirmed
  the existing 0.3.0 POMs already consume `bluetape4k-bom` 1.10.0.
- Git tag inspection showed 0.3.0 used `bluetape4k-exposed` 1.9.2.
- Current `develop` CI passed after the Exposed BOM implementation-scope change.

## Future Guidance

When a stable release line already carries the core BOM but not an adjacent
ecosystem BOM, prefer a patch release instead of reopening the whole minor
milestone.
