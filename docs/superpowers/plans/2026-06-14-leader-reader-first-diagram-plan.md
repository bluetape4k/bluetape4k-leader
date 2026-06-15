# bluetape4k-leader Reader-First Diagram Plan

## Current Directive

This plan supersedes the earlier batch-first and Graphviz-evidence attempts.
The root README diagrams must be rebuilt one asset at a time from current
README and source evidence.

Rules now in force:

- Do not use batch scripts or recreate deleted diagram batch entrypoints.
- Do not use Graphviz, DOT, plain-layout files, or generated evidence images.
- Do not use existing leader rendered PNGs as the source model.
- Use `Architects Daughter` and `Comic Mono` only.
- Keep README embeds on PNG assets.
- Inspect each final PNG directly before claiming completion.

## Reference Style

Allowed visual references:

- bluetape4k wiki best-practices approved assets.
- bluetape4k-projects root README diagrams.
- bluetape4k-graph root README diagrams.

These references guide layout discipline only: outer decorator, title hierarchy,
layer bands, compact cards, restrained connectors, and sequence lifeline
spacing. They do not replace leader README/source evidence.

## Root README Asset Plan

| Asset | Kind | Reader question | Source evidence | Success condition |
|---|---|---|---|---|
| `root-readme-overview-01` | Module overview | What problem does the repository solve and what module families exist? | `README.md`, `README.ko.md`, `settings.gradle.kts`, `leader-core` contracts, integrations, examples | A reader sees the core contracts, backend families, support modules, and example boundary without a generic grid. |
| `bluetape4k-leader-architecture-01` | Runtime map | What happens at the leader-election gate when competing callers contend? | README architecture/API sections, `LeaderElector`, `LeaderGroupElector`, `LeaderRunResult`, backend primitive families | Competing callers, ownership gate, elected/skipped/failed outcomes, telemetry, and backend primitive bank are visible without a generic layered layout. |
| `readme-table-relationships-erd-01` | Capability map | How do module catalog rows connect to backend capabilities, integrations, examples, and benchmarks? | README module/backend/example tables and benchmark section | Table-like compartments summarize capability relationships without pretending to be database schema. |
| `bluetape4k-leader-sequence-02` | Sequence | What happens when a single leader path runs or skips work? | `runIfLeader`, `LeaderRunResult`, README API examples | Acquisition, execution, result, release, and skip branch are visible with sequence-style lifelines. |
| `bluetape4k-leader-sequence-03` | Sequence | How do group slots grant limited concurrent ownership? | `runIfLeader(groupId, memberId)`, `SuspendLeaderGroupElector`, README group examples | Slot scan, accepted member, capacity branch, and release/update are clear without branch-frame clutter. |

Root README charts remain chart assets, not diagrams, unless explicitly
requested separately.

Image footer rule:

- Footer text is reader-facing only.
- Do not put source-evidence notes, validation notes, remediation notes, or
  generation/process details inside final images.
- Use a useful reader cue, a public contract note, or the repository URL:
  `github.com/bluetape4k/bluetape4k-leader`.

## Verification Checklist

| Step | Status | Evidence |
|---|---|---|
| Remove legacy generated evidence | PASS | `find docs/images/readme-diagrams ... '*.dot' '*.plain' '*-graphviz.*'` returned `0`. |
| Remove diagram batch scripts | PASS | `scripts/readme-diagrams/` and generated README diagram entrypoints were removed; only non-diagram utility scripts remain under `scripts/`. |
| Render PNG from SVG | PASS | Root README diagram SVGs and throughput chart SVG were rendered with `cairosvg`. |
| Visual inspection | PASS | Opened final PNGs individually for overview, runtime map, capability map, root sequences, and throughput chart; corrected visible route, footer, card-width, and chart-whitespace defects before completion. |
| README asset integrity | PASS | README and README.ko embed PNG assets; architecture and capability alt text now match the final diagram purpose. |
