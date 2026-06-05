# Issue 494 implementation review

## Scope

- Root README language switch placement.
- `scripts/check-readme-language-switches.mjs`.
- `.github/workflows/readme-language.yml`.
- Lesson entry for future localized README additions.

## Findings

- P0 = 0
- P1 = 0
- P2 = 0
- P3 = 0

## Gate

PASS.

## Evidence

- `node --check scripts/check-readme-language-switches.mjs`: PASS.
- `node scripts/check-readme-language-switches.mjs`: PASS (`groups=37; files=74; failures=0`).
- `actionlint .github/workflows/readme-language.yml`: PASS.
- `git diff --check`: PASS.

## Notes

The script enforces the visible locale order for every localized README sibling
set and blocks future Japanese/Chinese files unless Korean remains the second
entry in the ordered switch.
