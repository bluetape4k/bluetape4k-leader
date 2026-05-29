# README chart diagram skill lesson

## Context

README benchmark charts drifted from the `bluetape4k-diagram` visual contract:
some SVG sources used non-skill fallback fonts, and chart colors were stronger
than the required pastel tone.

## Decision

Regenerate all `docs/images/readme-charts/*.{svg,png}` assets with one shared
pastel palette and explicit font roles:

- `Architects Daughter` for titles and prominent labels.
- `Comic Mono` for subtitles, ticks, values, legends, and notes.

For local rendering, do not rely on `fc-match` alone. This machine can report a
fallback for `fc-match 'Architects Daughter'` even though `fc-list` and
`fc-scan` find the installed font file. Generated SVGs should bind the discovered
font file explicitly when needed.

## Outcome

All README chart assets now use PNG embeds backed by matching SVG sources with
pastel chart colors and explicit skill fonts.

## Verification

- `xmllint --noout docs/images/readme-charts/*.svg`
- `git diff --check`
- Rendered all chart PNGs with `rsvg-convert`.
- Previewed a six-chart contact sheet for clipping, spacing, font roles, and
  obvious overlap.

## Future agents

Before claiming a bluetape4k font is missing, check `fc-list`, direct font
paths, and `fc-scan`. If those show the font, configure rendering to use that
font instead of switching to another family because `fc-match` returned a
fallback.
