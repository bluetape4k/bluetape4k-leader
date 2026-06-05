# Issue 489 Design — Semantic Lock-State Line Colors

- Issue: #489 — `docs(diagram): apply semantic lock-state line colors`
- Parent: #486 — `epic(docs): refresh leader README diagrams with strict diagram gates`
- Work type: Type E Maintenance
- Primary skill: `bluetape4k-diagram`

## Problem

`bluetape4k-leader` README diagrams describe the same distributed-lock state
machine across core, backend modules, Spring AOP, and runnable examples. Many
sequence and flow diagrams currently use a neutral connector style even when the
paths mean different runtime outcomes:

- lock acquired and protected work executed
- lock contention and skipped work
- release or TTL cleanup
- crash recovery, retry, reacquire, or next scheduled run
- neutral setup, API call, or configuration path

When those states share the same line color, the diagram reader must infer the
meaning from text labels alone. The updated `bluetape4k-diagram` skill requires
semantic connector colors for scenario/work-order branches, with matching
arrowheads and route labels.

## Goals

1. Apply semantic connector colors to leader-operation diagrams where route
   meaning differs by lock state or execution outcome.
2. Keep a stable palette across the whole batch:
   - neutral/setup: muted gray
   - acquired/success/executed: green
   - skipped/failure/contention: pink/red
   - release/contention coordination: amber
   - retry/reacquire/next-run/recovery: purple
3. Ensure connector paths, arrowheads, and route labels use the same semantic
   color family.
4. Preserve lane separation and readable labels in dense routes. Color is not a
   substitute for geometry.
5. Enforce the rule in generator/evidence checks where practical, so future
   diagram changes fail before preview when route colors drift.

## Non-Goals

- Do not redesign root or module architecture layers in this issue. That belongs
  to #490.
- Do not add broad new example Scenario/Flow diagrams in this issue. That
  belongs to #491.
- Do not hand-edit every SVG asset. Prefer generator changes and targeted final
  SVG patches only when the generator cannot express a visual refinement.
- Do not recolor purely linear diagrams just to add variety. Color changes must
  communicate a meaningful lock-state or work-order distinction.

## Source Of Truth

- `bluetape4k-diagram` skill is the mandatory visual and gate contract.
- Current source code owns actor/step correctness for any diagram label.
- Existing README image inventory from `develop`:
  - README files: 75
  - README diagram embeds: 130
  - unique README diagram assets: 65
- Existing scripts:
  - `scripts/generate-example-readme-diagrams.mjs`
  - `scripts/generate-module-architecture-diagrams.mjs`
  - `scripts/generate-zookeeper-scheduler-readme-diagrams.mjs`
  - `scripts/regenerate-readme-diagram-graphviz-evidence.mjs`
- Existing evidence check currently fails on:
  - `examples-zookeeper-scheduler-architecture-01.svg`
  - `examples-zookeeper-scheduler-sequence-01.svg`

## Target Diagram Families

This issue targets diagrams with explicit runtime lock state:

- Root/core operation sequences:
  - `bluetape4k-leader-sequence-02`
  - `bluetape4k-leader-sequence-03`
  - `leader-core-sequence-02`
  - `leader-core-sequence-03`
- Backend operation sequences:
  - `leader-redis-lettuce-sequence-02`
  - `leader-redis-lettuce-sequence-03`
  - `leader-redis-redisson-sequence-02`
  - `leader-redis-redisson-sequence-03`
  - `leader-hazelcast-sequence-02`
  - `leader-hazelcast-sequence-03`
  - `leader-k8s-sequence-02`
- Spring/AOP operation sequence:
  - `leader-spring-boot-sequence-02`
- Example sequence/flow diagrams already carrying contention, skip, release, or
  retry semantics.

The implementation may skip a listed diagram only when source inspection proves
that the diagram has no semantic branch and would become decoration-only color.

## Palette Contract

| Semantic route | Color role | Intended examples |
|---|---|---|
| Neutral setup/API/config | muted gray | call setup, constructor, schedule tick, configuration |
| Acquired/executed/success | green | lock acquired, job body executed, protected work succeeds |
| Skipped/contention/failure | pink/red | lock miss, loser skips, rejected group slot, failed protected body |
| Release/coordination | amber | unlock, TTL cleanup, release after work, contention handoff |
| Retry/reacquire/next-run/recovery | purple | next scheduled run, crash recovery, reacquire after expiry |

The exact hex values should match the existing ZooKeeper scheduler semantic
palette unless a diagram-specific contrast issue requires a documented change:

- neutral: `#758297`
- acquired/success: `#43A76B`
- skipped/failure: `#EF5B7A`
- release/contention: `#D99A2B`
- reacquire/next-run: `#8B6EEB`

## Generator Requirements

- Define route color roles once per generator when the generator emits multiple
  colored route families.
- Generate arrow markers per semantic color so `marker-end` matches the path
  stroke.
- Route labels must use the route color or a visibly related darker/lighter
  member of the same color family.
- The evidence/check script should fail when a colored semantic route uses a
  default gray/black arrowhead or label.
- The check should report route id/name, diagram slug, and mismatched color.

## Visual Requirements

- Colored routes must still satisfy connector geometry gates:
  - endpoint attaches to node boundary
  - no 0-degree/tangent connector attachments
  - multi-segment bends are 90 degrees where avoidable
  - no non-endpoint component interior crossings
  - no connector lanes glued to unrelated box edges
- Dense or parallel routes must use lane offsets, labels, or a small legend when
  color meaning is not self-evident.
- Rendered PNGs must be inspected individually for all changed lock-state
  diagrams, not only via a contact sheet.

## Acceptance Criteria

- Every changed README still embeds PNG only.
- Every changed PNG has a matching SVG source.
- Every changed node-and-connector diagram has matching DOT, plain, Graphviz SVG,
  and Graphviz PNG evidence.
- Semantic line color checks are present in generator/evidence output for the
  changed batch.
- `node scripts/regenerate-readme-diagram-graphviz-evidence.mjs --check` passes.
- `xmllint --noout docs/images/readme-diagrams/*.svg` passes.
- README image-link check reports no missing assets and no SVG embeds.
- `git diff --check` passes.
- Visual QA evidence includes individual PNG inspection for changed lock-state
  diagrams.

## Risks

- Broad SVG churn can hide visual regressions. Mitigation: update generators and
  inspect only changed/suspect PNGs individually.
- Color can make diagrams noisier if applied to linear paths. Mitigation: color
  only meaningful state/work-order branches.
- Existing evidence checks may pre-fail on ZooKeeper scheduler assets. Mitigation:
  repair the evidence/generator gate first if it blocks validation for this
  batch.

## Step DoD

| Step | DoD |
|---|---|
| Spec | This file exists and defines scope, palette, non-goals, gates, and risks |
| Spec review | `P0 = 0`, `P1 = 0` before plan |
| Plan | Plan file maps target diagrams, generator edits, validation commands |
| Plan review | `P0 = 0`, `P1 = 0` before implementation |
| Implementation | Generator-driven semantic route colors and evidence checks |
| Validation | Generator, XML, link, diff, color, geometry, and visual QA evidence |
