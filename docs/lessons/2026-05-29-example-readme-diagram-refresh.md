# Example README Diagram Refresh

## Context

The `examples/*` README files needed source-derived scenarios and architecture
diagrams. Several examples already had sequence diagrams, while Kubernetes
Lease, Kubernetes Operator, and Rate Limiter needed sequence diagrams as well.

## Decision

Use source code as the workflow authority, but use existing repo diagrams of the
same type as the visual baseline before introducing new shapes. Sequence
participant headers must be rectangular boxes with a small corner radius, not
pill or ellipse badges. Card labels must be measured against their rendered box
width with the actual diagram fonts, not only visually estimated from character
count. The Graphviz evidence generator now checks these rules.

## Outcome

All example README locale pairs now include scenario text and PNG diagram embeds.
New architecture diagrams were added for examples that lacked them, and missing
sequence diagrams were added where the workflow needed one.

A follow-up pass widened every example architecture card whose rendered title
overflowed its box, including Kubernetes Operator, Rate Limiter, Migration Gate,
Tenant Aggregator, Webhook Poller, Ktor App, and Batch Scheduler.

## Verification

- `node scripts/regenerate-readme-diagram-graphviz-evidence.mjs --check`
- README image link check: `readmes=20 missing=0 svgEmbeds=0`
- `git diff --check`
- Visual contact sheet: `.omx/artifacts/examples-readme-diagrams-contact-sheet.png`
- Overflow fix contact sheet:
  `.omx/artifacts/fixed-architecture-overflow-contact-sheet.png`

## Future Guidance

Before drawing a new README diagram, inspect existing assets of the same diagram
type and preserve the stable shape language unless a source-driven reason
requires a documented divergence.

For generated SVG card diagrams, measure rendered text with the configured
`Architects Daughter` and `Comic Mono` font files. Fail the batch when any card
text exceeds its box bounds; fix by widening the card/canvas or changing the
layout direction before rendering PNGs.
