# Issue 375 release notes

## Context

Milestone 0.2.2 follows the published 0.2.1 release, but the public README
dependency snippets and changelog compare links still pointed at 0.2.0, skipping
the 0.2.1 release line.

## Decision

Use `baseVersion=0.2.2` as the public coordinate for README snippets and add
explicit 0.2.2 changelog coverage for the milestone issues already represented
by PRs. Keep root and module-level English/Korean README dependency blocks
aligned by updating the artifact coordinates mechanically.

## Outcome

Root and module-level `README.md` / `README.ko.md` files now advertise `0.2.2`
for public dependency snippets. `CHANGELOG.md` includes 0.2.1 and 0.2.2 sections
plus compare links from `0.2.2` to `HEAD`.

## Verification

- `git diff --check`
- `rg -n '0\\.2\\.0|0\\.2\\.1|0\\.2\\.2' README.md README.ko.md CHANGELOG.md`
- `rg -n 'io\\.github\\.bluetape4k\\.leader:[^:\"]+:0\\.2\\.0' --glob 'README*.md'`
