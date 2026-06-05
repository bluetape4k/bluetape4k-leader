# Issue 489 Semantic Route Colors

## Context

Leader README sequence diagrams used mostly neutral gray connectors, so lock
acquired, skipped, release, retry, and reacquire paths were hard to distinguish.

## Decision

Use a stable semantic connector palette across generated README diagrams:
neutral gray, leader/success green, skipped/failure pink, contention/release
amber, and reacquire/next-run purple. Store the tone on each semantic route as
`data-route-tone` and validate stroke plus arrow marker consistency in the
Graphviz evidence script.

## Outcome

- Added `scripts/apply-lock-state-line-colors.mjs` for route-order based
  semantic coloring of existing sequence SVG assets.
- Extended `scripts/regenerate-readme-diagram-graphviz-evidence.mjs` with a
  semantic color gate.
- Added `scripts/compact-sequence-call-spacing.mjs` after rendered review
  showed excessive function-call spacing in generic sequence diagrams.
- Enlarged ZooKeeper scheduler sequence canvas/header width instead of accepting
  text overflow.

## Verification

- `node scripts/generate-zookeeper-scheduler-readme-diagrams.mjs`
- `node scripts/apply-lock-state-line-colors.mjs`
- `node scripts/compact-sequence-call-spacing.mjs`
- `node scripts/regenerate-readme-diagram-graphviz-evidence.mjs --check`
- `xmllint --noout` for all README diagram SVGs
- README image-link check for 75 README files
- `git diff --check`

## Future Guidance

When line routing or label fit is tight, enlarge the diagram area first. Do not
ship semantic color changes without matching arrowheads, label color, and an
evidence gate that fails stale route styling.

For sequence diagrams, reduce excessive vertical spacing by moving complete
message groups, not by uniformly scaling y coordinates. Uniform scaling can
make fixed-height label boxes collide with call or return lines.
