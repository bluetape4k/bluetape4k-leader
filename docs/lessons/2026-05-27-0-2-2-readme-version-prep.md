# 2026-05-27 0.2.2 README Version Prep

## Context

The 0.2.2 patch fixes were merged before stable release publication, but the
root README locale set only pinned individual dependency snippets. It did not
make the current stable version obvious or show the BOM-first dependency path.

## Decision

Add an explicit `0.2.2` stable-version line near the top of both README files
and make the Gradle quick start start with the published
`bluetape4k-leader-bom` coordinate.

## Outcome

Release users can either import the BOM and omit module versions, or use the
direct module snippets that remain pinned to `0.2.2`.

## Verification

Run `git diff --check` and grep the README locale set for `0.2.2` and BOM
coordinates before opening the documentation PR.

## Future Guidance

For each stable release, update both README locale files with the visible
current version and keep BOM-first dependency instructions ahead of direct
module coordinates.
