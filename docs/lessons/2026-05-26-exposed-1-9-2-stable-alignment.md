# Exposed 1.9.2 Stable Alignment

## Context

`bluetape4k-exposed` 1.9.2 is published on Maven Central, but leader still used
`bluetape4k-exposed = 1.9.2-SNAPSHOT` for Exposed test fixtures.

## Decision

Move the direct `bluetape4k-exposed` catalog version to the stable `1.9.2`
release while keeping the existing `bluetape4k` core BOM at `1.9.2`.

## Outcome

Leader's Exposed JDBC/R2DBC modules no longer depend on the temporary snapshot
line for Exposed helpers.

## Verification

- Maven Central HTTP 200 for `bluetape4k-exposed-bom:1.9.2`

## Future Notes

After a stable Exposed release is visible, downstream direct references should
move from the matching snapshot to the stable BOM before the next leader
release-prep.
