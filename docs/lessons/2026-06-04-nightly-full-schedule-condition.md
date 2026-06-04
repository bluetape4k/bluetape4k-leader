# Lessons Learned — Nightly full schedule condition (2026-06-04)

**Related issue**: #470

## Context

The Nightly cron minute was staggered to reduce Central snapshot metadata
contention. Full-scope scheduled jobs still compared `github.event.schedule`
against the old Sunday cron string, so weekly full jobs could be skipped.

## Decision

Keep the staggered cron, and update full-scope job conditions to compare against
the repository's current Sunday schedule.

## Verification

- `actionlint .github/workflows/nightly-tests.yml`
- `git diff --check`
- Schedule-condition audit: no old `0 19 * * 0` full-job condition remains.

## Future Rule

When changing a scheduled cron string, update every `github.event.schedule`
comparison in the same workflow.
