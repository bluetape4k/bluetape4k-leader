# README Diagram Graphviz Evidence

## Context

README diagram SVG/PNG assets existed without adjacent Graphviz evidence files.
The diagram skill now treats Graphviz `.dot`, `.plain`, and sketch SVG as
mandatory route/layout evidence for node-and-connector README diagrams.

## Decision

Add a repository script that extracts final SVG nodes/routes, writes matching
Graphviz evidence beside each README diagram, injects SVG metadata pointing to
that evidence, and renders PNGs with explicit font discovery. The script also
checks normal-card text-block vertical centering so top- or bottom-biased card
labels fail before preview. Follow-up review found that Graphviz evidence had
tracked final coordinates but had not proven that final boxes avoided overlap or
that sequence diagrams followed the required outer/internal spacing rule, so
those checks were promoted into the same script.

## Outcome

All final `docs/images/readme-diagrams/*.svg` assets now have matching `.dot`,
`.plain`, `*-graphviz.svg`, and `*-graphviz.png` evidence. Every SVG artifact
has a PNG counterpart. Required fonts are resolved from the local font directory
and passed to the render path through fontconfig instead of accepting renderer
fallback.

`bluetape4k-leader-architecture-01` was re-spaced so `leader-core` remains on
the left and the Exposed JDBC/R2DBC modules remain on the right without box
overlap. Sequence diagrams were widened where needed so participant headers have
generous outer margins, while message labels now sit close to their arrows.

## Verification

- `node scripts/regenerate-readme-diagram-graphviz-evidence.mjs`
- `node scripts/regenerate-readme-diagram-graphviz-evidence.mjs --check`
- `xmllint --noout docs/images/readme-diagrams/*.svg`
- `git diff --check`
- README image link/evidence checker: `missing=0`
- Node overlap gate: `overlaps=0` for `bluetape4k-leader-architecture-01`
- Sequence spacing gate: participant margins at least 60px and label-to-arrow
  gaps at most 10px; current generated sequence set reports `sequence_spacing_failures=0`
- Visual contact sheet: `.omx/artifacts/leader-readme-diagram-contact-sheet.png`
- Sequence visual contact sheet: `.omx/artifacts/sequence-final-contact-sheet.png`
- SVG/PNG pair check: `svg=70`, `png=70`, `missing_svg_png_pairs=0`

## Future Guidance

For README diagram regeneration, never stop at final SVG/PNG existence. Require
Graphviz evidence files, matching PNG previews for every generated SVG,
SVG metadata, intended font resolution, text vertical alignment checks, and
visual preview evidence before reporting completion. If final SVG coordinates
are used as fixed Graphviz positions, still verify final-node overlap directly;
matching Graphviz evidence does not prove the final visual spacing is acceptable.
For sequence diagrams, verify both outer participant/header margin and compact
label-to-arrow spacing before preview.
