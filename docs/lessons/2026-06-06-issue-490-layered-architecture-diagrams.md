# Issue #490 Layered Architecture Diagrams Lesson

## Context

Issue #490 refreshed README architecture diagrams after the #489 lock-state route-color work. The user had repeatedly flagged weak layer placement, route readability, and inconsistent README language links.

## Decision

Use `scripts/apply-layered-architecture-bands.mjs` as a deterministic layer-band pass over the existing Graphviz-backed SVG/PNG assets. Keep the route geometry stable, but add domain-named layer bands and validate layer containment before rendering.

## Outcome

- Added layer bands to 16 root/module architecture and class-style diagram pairs.
- Used horizontal row bands for stacked layouts and column bands for DynamoDB/K8s actor/elector/state layouts.
- Normalized README language switches across every README pair to the visible `English | 한국어` form.

## Verification

- `node scripts/apply-layered-architecture-bands.mjs`: every target reported zero endpoint, bend, crossing, margin, title-gap, and layer-containment failures.
- `node scripts/regenerate-readme-diagram-graphviz-evidence.mjs --check`: `diagrams=65 failures=0`.
- Contact sheet plus individual PNG inspection covered the root, DynamoDB, K8s, and Exposed JDBC diagrams.
- `xmllint --noout`, README image-link check, and `git diff --check` passed.

## Future Guidance

- Do not accept fallback names such as `Layer 4`; every band label must be domain meaningful.
- If row-based clustering collapses a diagram into one large band, switch to a larger canvas or a column-oriented layer model before review.
- Keep contact-sheet triage plus individual inspection for suspect diagrams; automated gates do not replace rendered PNG review.
