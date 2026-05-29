# Module Architecture Diagram Coverage

## Context

`leader-micrometer` and `leader-spring-boot` already had architecture diagrams,
but their grid-like placement made relationships hard to follow. `leader-etcd`,
`leader-dynamodb`, and `leader-consul` had no README architecture diagram.

## Decision

Use freer module-level layouts that group inputs, auto-configuration, electors,
backend clients, backend state, and exporters by relationship instead of forcing
every module into the same row/column grid. Generate module architecture SVGs
from `scripts/generate-module-architecture-diagrams.mjs`, then run the shared
Graphviz evidence/PNG regeneration gate.

## Outcome

`leader-micrometer` and `leader-spring-boot` now use clearer relationship-first
architecture layouts. `leader-etcd`, `leader-dynamodb`, and `leader-consul` now
embed architecture PNG diagrams in both English and Korean READMEs, with matching
SVG, DOT, plain, Graphviz SVG, and Graphviz PNG evidence files.

## Verification

- `node scripts/regenerate-readme-diagram-graphviz-evidence.mjs --check`
- `git diff --check`
- README image link check: `missing=0`
- Visual contact sheet:
  `.omx/artifacts/module-architecture-diagrams-contact-sheet.png`

## Future Guidance

When a backend README lacks an architecture diagram, add it with the README
locale pair in the same change. Prefer relationship-first placement over a
uniform grid when the graph has multiple independent entry paths.
