# Issue #419 Strategic Election Example

## Context

The root README documented strategic election APIs, but the examples catalog had
no runnable backend-neutral weighted election scenario.

## Decision

Added `examples/strategic-election` as a local strategic-election demo. The
example reuses `WeightedScorer`, `ScoredElectionStrategy`, and
`LocalStrategicLeaderElector` instead of adding new `leader-core` API.

## Outcome

The example selects one maintenance node with weighted health, capacity,
success-rate, and idle-time scoring. Non-winner nodes return skipped reports.

## Verification

- `./gradlew projects`
- `./gradlew :examples:strategic-election:test`
- `./gradlew :examples:strategic-election:run`
- `actionlint .github/workflows/ci.yml .github/workflows/examples.yml`
- `git diff --check`

## Future Guidance

Backend-neutral examples should still wire the full module chain: settings,
root README locale set, repo-local AGENTS module list, CI path filter/job,
weekly Examples workflow, and test resources.
