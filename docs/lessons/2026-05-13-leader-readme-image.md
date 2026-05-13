# Leader README Hero Image

## Context

The project README needed a representative image that matches the bluetape4k
profile workbench style while making the leader-election domain visible.

## Decision

Store the generated raster asset in `docs/assets/leader-election-workbench.png`
and reference the same relative path from both `README.md` and `README.ko.md`.

## Outcome

The root README now opens with a leader-election workbench illustration before
the introductory copy in both locales.

## Verification

- Confirmed the asset exists as a PNG under `docs/assets`.
- Checked README references and Markdown diff formatting.

## Future Guidance

Keep README hero images inside `docs/assets` so localized README files can share
one stable relative path.
