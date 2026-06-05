# 2026-06-06 - Issue 494 README language switches

## Context

Issue #494 tracks the repository-wide README language switch convention after
PR #493 normalized the current English/Korean links.

## Decision

Keep the visible language order stable as `English | 한국어`, then append future
Japanese and Chinese entries after Korean. Enforce the rule with a lightweight
Node script instead of relying on manual review.

## Outcome

Root README files now place the language switch directly below the H1 title, and
the new `README Language` workflow runs the script for README or validation
changes without forcing heavyweight CI on docs-only PRs.

## Verification

- `node --check scripts/check-readme-language-switches.mjs`
- `node scripts/check-readme-language-switches.mjs`
- `actionlint .github/workflows/readme-language.yml`
- `git diff --check`

## Future Guidance

When adding `README.ja.md` or `README.zh.md`, add the sibling file first and let
`scripts/check-readme-language-switches.mjs` force every localized README to use
the same ordered switch.
