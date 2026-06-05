# Issue 489 Plan — Semantic Lock-State Line Colors

- Issue: #489 — `docs(diagram): apply semantic lock-state line colors`
- Spec: `docs/superpowers/specs/2026-06-05-issue-489-lock-state-line-colors-design.md`
- Spec review: `docs/review/2026-06-05-issue-489-spec-review.md`

## Gate State

| Gate | Status | Evidence |
|---|---:|---|
| Spec | PASS | Spec file exists |
| Spec review | PASS | `P0 = 0`, `P1 = 0` |
| Plan | PASS | This file |
| Plan review | PASS | `docs/review/2026-06-05-issue-489-plan-review.md`; `P0 = 0`, `P1 = 0` |

## Implementation Strategy

Use generator-first edits. Do not broad-edit final SVG files until the generator
or evidence script proves the target route model.

## Work Order

1. Baseline evidence repair
   - Re-run `node scripts/regenerate-readme-diagram-graphviz-evidence.mjs --check`.
   - Inspect the two current failures:
     - `examples-zookeeper-scheduler-architecture-01.svg`
     - `examples-zookeeper-scheduler-sequence-01.svg`
   - Fix the generator/evidence metadata mismatch before using the check as a
     final gate.
   - DoD: baseline evidence check can pass on `develop`-equivalent generated
     assets or has a documented source-backed local exception.

2. Shared route color contract
   - Add a shared semantic route color table to the relevant generator(s), using
     the ZooKeeper scheduler palette as the default.
   - Ensure arrow markers are generated per route color.
   - Ensure route labels use the same semantic color family.
   - DoD: generated SVGs contain no colored path with stale default gray/black
     arrowhead markers for semantic routes.

3. Evidence script color gate
   - Extend `scripts/regenerate-readme-diagram-graphviz-evidence.mjs --check`
     or a companion check inside the generator so mismatched path/marker/label
     colors fail.
   - Report diagram slug and route id/name for mismatch failures.
   - DoD: check output includes a semantic color consistency summary for changed
     diagrams.

4. Root/core operation sequences
   - Apply route colors to:
     - `bluetape4k-leader-sequence-02`
     - `bluetape4k-leader-sequence-03`
     - `leader-core-sequence-02`
     - `leader-core-sequence-03`
   - Route semantics:
     - acquisition/executed path: green
     - contention/skip path: pink/red
     - release path: amber
     - next slot/reacquire/retry path: purple when present
     - neutral setup/API path: gray
   - DoD: rendered PNGs inspected individually.

5. Backend operation sequences
   - Apply route colors where the diagram has lock state branches:
     - Redis Lettuce sequence 02/03
     - Redis Redisson sequence 02/03
     - Hazelcast sequence 02/03
     - Kubernetes sequence 02
   - Skip only when source/diagram inspection proves the path is linear and
     color would be decorative.
   - DoD: skip list is documented in the final report with reason.

6. Spring/AOP operation sequence
   - Apply semantic route colors to `leader-spring-boot-sequence-02` if the
     reentrant flow has distinguishable acquired/reentrant/release/error paths.
   - DoD: no dense-route readability regression; use labels/lane offsets if
     needed.

7. Existing example operation diagrams
   - Apply route colors only to existing example diagrams that already include
     contention, skip, release, retry, recovery, or next-run semantics.
   - Do not add new Scenario/Flow diagrams here; #491 owns that.
   - DoD: changed example PNGs inspected individually or listed as unchanged
     because no semantic branch exists.

8. README and alt text cleanup
   - Update README alt text only when it is currently generic or broken near a
     changed diagram.
   - Preserve localized README prose unless touching the same section for
     diagram correctness.
   - DoD: README image-link check passes and no README embeds SVG directly.

9. Lesson and review artifacts
   - Add a concise lesson under `docs/lessons/`.
   - Add implementation review artifact under `docs/review/`.
   - DoD: PR includes durable evidence, not only transient `.omx/artifacts`.

## Validation Commands

Run after implementation:

```bash
node scripts/regenerate-readme-diagram-graphviz-evidence.mjs --check
xmllint --noout docs/images/readme-diagrams/*.svg
node -e "<README image link check>"
git diff --check
```

Run any touched generator commands explicitly, for example:

```bash
node scripts/generate-zookeeper-scheduler-readme-diagrams.mjs
node scripts/generate-example-readme-diagrams.mjs
node scripts/generate-module-architecture-diagrams.mjs
node scripts/regenerate-readme-diagram-graphviz-evidence.mjs
```

Only run broad generators when the touched diagram family requires it. Avoid
unnecessary asset churn.

## Visual QA

- Create a contact sheet for changed diagrams.
- Inspect each changed lock-state PNG individually at readable size.
- Check:
  - route color meaning is obvious or labeled
  - arrowheads match route colors
  - route labels belong to the same semantic color family
  - line colors do not hide cramped geometry
  - connector endpoint angle and clearance still look correct

## Stop Conditions

- Stop before implementation if plan review reports `P0 > 0` or `P1 > 0`.
- Stop before PR if any mandatory diagram gate is missing, skipped, or failing.
- Stop before PR if rendered PNG inspection shows visible overlap, cramped
  routing, unmatched arrowhead colors, or decoration-only color noise.
