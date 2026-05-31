# Leader 0.3.0 Release Prep

## Context

The 0.3.0 milestone merged the final release-batch PRs after CI passed:
preview backend release gates, a virtual-thread runner example, and a Redisson
watchdog-style long-running job example.

## Decision

Prepare the stable release from `develop` after all issue PRs are merged. Keep
`baseVersion=0.3.0` and `snapshotVersion=` unchanged, promote the changelog
content from `Unreleased` to `[0.3.0]`, and update README dependency snippets to
the stable `0.3.0` coordinate.

## Outcome

The release-prep branch records the public release notes and user-facing
dependency coordinates before the tag is cut. Remaining assigned issues are
kept in Backlog for a later minor release.

## Verification

- `gh pr checks` passed for #454, #455, and #456 before merge.
- `./gradlew properties --no-daemon | rg '^version:|^group:'` resolves
  `io.github.bluetape4k.leader:0.3.0`.
- Run `git diff --check`, `actionlint`, and release-prep CI before tagging.

## Follow-up

After publishing 0.3.0, verify Maven Central HTTP 200 for the BOM and at least
one representative module POM, then reopen `develop` to the next minor line.
