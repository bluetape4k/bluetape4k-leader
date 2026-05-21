# README visual order

## Context

The Korean root README placed the architecture section before the source-backed
overview diagram, while the English README already exposed the overview first.

## Decision

Keep root overview visuals immediately after the feature summary in every
localized README, then place architecture detail below them.

## Outcome

`README.ko.md` now matches the English visual order and has a descriptive
multi-leader sequence image label.

## Verification

- `git diff --check`
- `xmllint --noout` for README SVG assets
- README image-link existence scan

## Next

Future README edits should preserve Overview Diagram and Module Composition
Chart as the first visual explanation readers see.
