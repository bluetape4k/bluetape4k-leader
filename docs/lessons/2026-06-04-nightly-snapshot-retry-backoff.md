## Context

Nightly and CI matrix jobs failed intermittently while resolving upstream `1.11.0-SNAPSHOT` artifacts from Central snapshots. The local Central metadata checks returned HTTP 200, while GitHub-hosted runners intermittently received HTTP 403.

## Decision

Use the same retry posture across CI, Nightly, and example workflow Gradle steps: five attempts with a 30 second wait between attempts.

## Outcome

The workflow now gives transient Central snapshot metadata failures more time to recover before marking module tests failed.

## Verification

- `git diff --check`
- `actionlint .github/workflows/*.yml`

## Future Guidance

When a downstream bluetape4k repo consumes unreleased upstream snapshots, stabilize upstream first, then rerun downstream Nightly after the upstream CI and Nightly gates are green.
