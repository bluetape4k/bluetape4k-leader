# Nightly Snapshot Refresh

## Context

Nightly restores Gradle caches and consumes mutable bluetape4k Central snapshot artifacts.
Stale snapshot metadata can make module jobs fail before tests execute.

## Decision

Pass `--refresh-dependencies` to Nightly Gradle invocations so snapshot metadata
is rechecked on every scheduled or manually dispatched run.

## Outcome

Nightly keeps cache reuse for build state while avoiding stale Maven snapshot
metadata during dependency resolution.

## Verification

- `actionlint .github/workflows/nightly-tests.yml`
- `git diff --check`

