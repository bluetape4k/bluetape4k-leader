# Nightly Snapshot Refresh

## Context

Nightly restores Gradle caches and consumes mutable bluetape4k Central snapshot artifacts.
Stale snapshot metadata or simultaneous Central snapshot metadata requests can
make module jobs fail before tests execute.

## Decision

Pass `--refresh-dependencies` to Nightly Gradle invocations and stagger the
scheduled cron minute so snapshot metadata is rechecked without starting every
downstream repository at the same time.

## Outcome

Nightly keeps cache reuse for build state, refreshes mutable metadata, and
reduces scheduled cross-repository Central snapshot contention.

## Verification

- `actionlint .github/workflows/nightly-tests.yml`
- `git diff --check`
