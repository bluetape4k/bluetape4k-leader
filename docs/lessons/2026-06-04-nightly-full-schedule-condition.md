# Lessons Learned — Nightly full schedule condition (2026-06-04)

**Related issue**: #470

## Context

The Nightly cron minute was staggered to reduce Central snapshot metadata
contention. Full-scope scheduled jobs still compared `github.event.schedule`
against the old Sunday cron string, so weekly full jobs could be skipped.

The follow-up PR also showed that CI can hit the same snapshot metadata failure
path as Nightly when its Gradle commands do not use `--refresh-dependencies`.

## Decision

Keep the staggered cron, and update full-scope job conditions to compare against
the repository's current Sunday schedule.

Apply the same snapshot refresh and GitHub runner configuration-cache avoidance
to CI Gradle invocations so PR checks validate the branch under the same
dependency-resolution policy expected from Nightly.

## Verification

- `actionlint .github/workflows/nightly-tests.yml`
- `git diff --check`
- Schedule-condition audit: no old `0 19 * * 0` full-job condition remains.
- CI/Nightly Gradle audit: every `./gradlew` call includes
  `--refresh-dependencies`.

## Future Rule

When changing a scheduled cron string, update every `github.event.schedule`
comparison in the same workflow.
When changing snapshot dependency policy, audit both `.github/workflows/ci.yml`
and `.github/workflows/nightly-tests.yml`.
