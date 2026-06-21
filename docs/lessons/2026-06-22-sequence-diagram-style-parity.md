# 2026-06-22 Sequence Diagram Style Parity

## Context

The README diagram refresh repeatedly passed XML, marker, and geometry checks
while several `*-sequence-*` assets still did not look like the established
best-practices sequence family. The clearest local reference was
`leader-redis-lettuce-sequence-*`: handwritten title, participant headers,
vertical lifelines, activation bars, horizontal message lanes, pill labels,
subdued `alt` and `else` regions, and solid fixed-size arrowheads.

The failure was not a single broken marker or route. Some diagrams had valid
SVG and rendered PNG output, but still kept older flowchart-like or
module-specific styling. Contact sheets made the drift visible only after the
whole sequence family was placed side by side.

## Decision

Treat sequence style parity as a visual contract, not only as an SVG checklist.
For this repository, a diagram named `*-sequence-*` must follow the
`leader-redis-lettuce-sequence-*` family unless a future design note records an
explicit exception.

The repair pass normalized all sequence diagrams and redrew the outliers
instead of trying to preserve old local styles:

- `bluetape4k-leader-sequence-02/03`
- `leader-hazelcast-sequence-02/03`
- `leader-k8s-sequence-02`
- `leader-ktor-sequence-01`
- `leader-redis-redisson-sequence-02/03`
- `leader-spring-boot-sequence-01/02`

The example sequence set was also kept on the same visual grammar so the README
does not mix best-practices sequence diagrams with rougher example-specific
variants.

## Verification

Use these checks for future sequence refreshes:

```bash
xmllint --noout docs/images/readme-diagrams/*-sequence-*.svg
python3 /Users/debop/.codex/skills/bluetape4k-diagram/references/diagram-geometry-audit.py docs/images/readme-diagrams/*-sequence-*.svg
python3 /Users/debop/.codex/skills/bluetape4k-diagram/references/diagram-endpoint-audit.py docs/images/readme-diagrams/*-sequence-*.svg
git diff --check -- docs/images/readme-diagrams
```

Then run a marker audit that rejects sequence markers unless they are
`16x16`, use `markerUnits="userSpaceOnUse"`, and harden the marker body with
`stroke-dasharray="none"`.

Static checks are not sufficient. Render with CairoSVG, inspect high-risk PNGs
at full size, and create contact sheets for both `examples-*-sequence-*` and
leader `*-sequence-*` diagrams. If any thumbnail looks like a different diagram
family, reopen the full-size PNG and redraw it before reporting completion.

## Future Guidance

Do not claim sequence diagram checklist completion by checking only:

- file names
- XML validity
- marker size attributes
- successful PNG rendering
- generic geometry audit success

First compare the rendered PNG against the current best-practices sequence
family. The expected visual signals are participant headers, lifelines,
activation bars, horizontal message lanes, pill labels that do not sit on call
lines, subdued branch regions, and solid arrowheads even on dashed return
messages.

When user review reports that a sequence diagram does not match
best-practices, audit every `*-sequence-*` asset as a family. Fixing one named
diagram is not enough if the contact sheet still shows another local style.
