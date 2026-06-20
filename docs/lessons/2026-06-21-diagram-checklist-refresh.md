# 2026-06-21 Diagram Checklist Refresh

## Context

The README and example README diagram set had already moved away from Graphviz,
but the rendered SVGs still carried checklist drift:

- connector markers without explicit `markerUnits`
- dashed connector styles bleeding into marker heads
- sharp orthogonal connector corners that failed geometry audit
- fallback fonts outside the shared handwritten font pair
- chart notes using source/process wording in the visible image
- missing shared catalog icons on cards that represent real services or
  infrastructure

## Decision

Keep the current reader-facing layouts and repair only checklist-level drift.
The repair pass is centralized in `scripts/repair-readme-svg-checklist.mjs` so
future refreshes can re-run it after generator output changes.

The README hero raster under `docs/assets/leader-election-workbench.png` is an
illustration, not a README diagram/chart source asset. It remains PNG-only by
design, as recorded in the earlier hero-image lesson.

Icon placement is handled separately by
`scripts/apply-readme-svg-icons.mjs`. The script uses only shared catalog icons
from `/Users/debop/work/bluetape4k/bluetape4k-wiki/docs/icons`, keeps code-only
cards text-only, and emits `data-bluetape4k-icon` plus `data-icon-source`
metadata so follow-up audits can verify provenance.

## Verification

Run these after regenerating or editing README diagram/chart SVGs:

```bash
node scripts/repair-readme-svg-checklist.mjs --check
node scripts/apply-readme-svg-icons.mjs
xmllint --noout docs/images/readme-diagrams/*.svg docs/images/readme-charts/*.svg
python3 /Users/debop/.codex/skills/bluetape4k-diagram/references/diagram-geometry-audit.py docs/images/readme-diagrams/*.svg docs/images/readme-charts/*.svg
rg -n "Arial|sans-serif|monospace|Comic Sans MS|Source:|Generated|validation|Graphviz|DOT|context-stroke" docs/images/readme-diagrams docs/images/readme-charts -g '*.svg'
git diff --check
```

Render PNGs through CairoSVG and inspect contact sheets plus high-risk originals
before reporting the diagram set as done.

For this refresh, the icon audit found 42 SVG files with 61 catalog icons, 0
missing catalog sources, and 0 suspicious code-card placements.

Follow-up review caught additional visual regressions that basic XML/render
checks did not prove away: sequence diagrams must follow the best-practices
participant/lifeline/message-lane style, connector routes must stay orthogonal
when a diagram mixes bent and diagonal lines, marker heads must remain solid on
dashed lines, and non-sequence connectors must not bend through card interiors.

The repair script now normalizes simple diagonal connector paths into rounded
orthogonal paths, hardens marker child paths against inherited dash styles, and
keeps sequence arrowheads explicit. The icon script strips and reapplies managed
icon images before placement so size and positioning changes are idempotent.
Non-Redis icons were present in the SVG source but too small on several rendered
cards; the visible minimum icon size was raised before re-rendering.

Extra verification used for the follow-up:

- `straightDiagonal=0`
- `smallFilledArrowMarkers=0`
- `diagonalLineSegments=0`
- `markerDashLeaks=0`
- `missingFontPair=0`
- `weakRouteStrokeStyles=0`
- `weakCardStrokeStyles=0`
- `nonSequenceCardInteriorBends=0`
- icon distribution: Redis 21, Consul 6, DynamoDB 8, etcd 4, Kubernetes 4,
  database 6, Prometheus 6, Grafana 4, ZooKeeper 1, Spring Boot 1
- representative visual inspection: sequence contact sheet, all-diagram contact
  sheet, Consul sequence/flow, DynamoDB sequence, Kubernetes sequence,
  Prometheus architecture, strategic election flow, and leader core class

A later visual review found that valid XML and the first geometry audit still
allowed too-small filled arrow markers, weak 2px route strokes, one thin card
stroke, and routes that visually ended near the `Ownership Gate` rather than on
a real card boundary. The repair script now normalizes filled arrowheads to the
README-scale 10x10 marker family, blocks dash inheritance with an important
style override, rejects diagonal line segments inside multi-point paths, and
raises route/card stroke styles that fall below the baseline. The repository
architecture overview was manually redrawn so the ownership gate is a rounded
card with perpendicular boundary attachments instead of a decision diamond with
ambiguous empty-space arrow endpoints.

The final full-size PNG review found three remaining classes of checklist
drift that contact sheets and XML checks alone did not expose:

- tight rounded connector repairs had produced cubic `C` curves instead of the
  expected `Q`-based orthogonal bends
- several sequence diagrams had `marker-end` via CSS but marker bodies rendered
  as `fill="none" stroke="none"`, making arrowheads invisible in PNG output
- class/architecture diagrams still had inline marker paths below the baseline
  stroke width

The repair script now keeps tight `Q` bends as quadratics, normalizes arrow
marker colors from marker ids when the original marker body is transparent, and
raises inline marker path/line strokes to at least 2.5px. The final visual pass
inspected refreshed contact sheets for architecture, flow, scenario, sequence,
class/ERD, charts, and root/core groups, plus full-size checks for
`examples-cache-warmer-architecture-01`,
`examples-cache-warmer-flow-01`,
`examples-migration-gate-scenario-01`,
`leader-core-sequence-02`,
`leader-core-sequence-03`,
`leader-hazelcast-sequence-02`,
`leader-hazelcast-sequence-03`,
`leader-spring-boot-sequence-01`,
`leader-spring-boot-architecture-01`,
`leader-dynamodb-architecture-01`,
`leader-exposed-core-erd-01`,
`leader-mongodb-class-01`, and `leader-zookeeper-class-01`.

Final audit evidence:

- `connectorCurves=0`
- `diagonalLineSegments=0`
- `smallFilledArrowMarkers=0`
- `markerDashLeaks=0`
- `sequenceArrowMarkersNone=0`
- `sequenceMessagePathsWithoutEffectiveMarker=0`
- `weakInlineMarkerStrokes=0`
- `missingFontPair=0`
- `weakRouteStrokeStyles=0`
- `weakCardStrokeStyles=0`

A later user visual review exposed that the previous pass still over-relied on
contact sheets and SVG-level checks. The specific failures were clustered:

- example sequence diagrams did not consistently follow the leader-core
  best-practices style
- example flow diagrams still read as horizontal scenario flows instead of
  vertical order-of-work flowcharts
- several card connectors attached at shallow side angles, crossed unrelated
  cards, or used rounded bends that were visually too tight
- `leader-hazelcast-class-01` showed dashed-line marker artifacts in PNG even
  when the SVG marker body looked solid

For this repair, the example sequence set was regenerated with participant
headers, vertical lifelines, horizontal message lanes, and larger row spacing.
The example flow set was regenerated as a vertical flowchart pattern. The
Hazelcast class diagram now draws dashed-line arrowheads as direct solid
geometry instead of relying on inherited marker rendering.

Do not mark future README diagram work complete until both are true:

1. User-reported files have been opened as full-size PNGs after CairoSVG render.
2. Pattern-wide files have been inspected via contact sheets and suspicious
   thumbnails are reopened full-size.

Extra evidence for this repair:

- full-size PNG review:
  `bluetape4k-leader-sequence-03`,
  `examples-batch-scheduler-sequence-01`,
  `examples-cache-warmer-architecture-01`,
  `examples-cache-warmer-flow-01`,
  `examples-cache-warmer-sequence-01`,
  `examples-consul-maintenance-sequence-01`,
  `examples-dynamodb-export-sequence-01`,
  `leader-etcd-architecture-01`,
  `leader-exposed-core-erd-01`,
  `leader-exposed-jdbc-class-01`,
  `leader-exposed-r2dbc-class-01`, and
  `leader-hazelcast-class-01`
- contact sheet review: all 17 `examples-*-sequence-01` PNGs and all 17
  `examples-*-flow-01` PNGs
- final static audit:
  `files=109`,
  `connectorPaths=437`,
  `msgPaths=285`,
  `missingFont=0`,
  `markerSmall=0`,
  `sequenceStyle=0`,
  `flowStyle=0`,
  `hazelDashMarkers=0`,
  `badConnectorSegments=0`,
  `seqMissing=0`

A later style review found that the whole `examples-*` set still looked too
rough compared with the best-practices catalog. Future checks must not stop at
"a path contains Q". First flag sharp `L/H/V` and tiny fake-round candidates,
then open the rendered PNG and confirm the corner actually reads as rounded.

For this repair, all 68 `examples-*` diagrams were regenerated from one
best-practices template family:

- architecture: layered topology with semantic bands and thick card/route
  strokes
- flow: vertical flowchart with Trigger, Prepare, Election, Leader Work, and
  Outcome bands
- scenario: branching workflow with success, skipped, and retry lanes
- sequence: leader-core-style participant headers, lifelines, activation bars,
  alternate branch frame, and wide message spacing

The final whole-set audit also repaired tight `Q` candidates that remained in
older non-example diagrams:

- `leader-core-sequence-02`
- `leader-core-sequence-03`
- `leader-mongodb-class-01`
- `leader-zookeeper-class-01`

Extra evidence for the all-example regeneration:

- regenerated SVG/PNG pairs: 68 `examples-*` diagrams
- post-processing idempotence:
  `repair-readme-svg-checklist.mjs --check` => `would_update=0`
  and `apply-readme-svg-icons.mjs` => `would_update=0 icons=0`
- full-set geometry audit: 109 diagram/chart SVGs, all
  `geometry_failures=0`
- static style audit:
  `files=109`,
  `missingFont=0`,
  `smallMarkers=0`,
  `dashedMarkers=0`
- full-size PNG review:
  `examples-cache-warmer-flow-01`,
  `examples-dynamodb-export-sequence-01`,
  `examples-zookeeper-scheduler-scenario-01`,
  `examples-prometheus-dashboard-architecture-01`,
  `leader-mongodb-class-01`, and
  `leader-zookeeper-class-01`
