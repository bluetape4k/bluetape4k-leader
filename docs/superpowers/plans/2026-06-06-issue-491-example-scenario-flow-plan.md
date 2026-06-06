# Issue 491 Example Scenario and Flow Diagram Plan

- Issue: #491 `docs(examples): add scenario and flow diagrams`
- Spec:
  `docs/superpowers/specs/2026-06-06-issue-491-example-scenario-flow-design.md`
- Branch: `docs/issue-491-example-scenario-flow`

## Ordered Steps

1. Baseline evidence
   - Run `node scripts/regenerate-readme-diagram-graphviz-evidence.mjs --check`.
   - Record expected clean baseline before editing.

2. Generator implementation
   - Add a focused example-flow generator, or extend the existing example
     generator only if the diff remains reviewable.
   - Generate:
     - `examples-dynamodb-export-architecture-01`
     - `examples-dynamodb-export-flow-01`
     - `examples-dynamodb-export-sequence-01`
     - `examples-*-flow-01` for every non-ZooKeeper example that already has
       Architecture and Sequence diagrams.
   - Print deterministic geometry summaries before any PNG/evidence rendering.
   - Use semantic connector tones for leader, skipped/failure, contention, and
     retry/reacquire paths.
   - Use layer bands in Flow diagrams and fail when nodes fall outside their
     assigned band or drift visibly toward the band bottom.

3. README updates
   - Update every touched `README.md` and `README.ko.md` pair.
   - Embed PNG only.
   - Use English diagram alt text and localized section headings:
     - English: `## Flow Diagram`
     - Korean: `## 플로우 다이어그램`
   - For DynamoDB Export, add Architecture, Flow, and Sequence sections.

4. Evidence regeneration
   - Run the generator.
   - Run `node scripts/regenerate-readme-diagram-graphviz-evidence.mjs`.
   - Run `node scripts/regenerate-readme-diagram-graphviz-evidence.mjs --check`.
   - The final check must report zero failures.

5. Visual QA
   - Create a contact sheet for the changed PNGs under `.omx/artifacts`.
   - Inspect the contact sheet.
   - Individually inspect dense/suspect diagrams and every DynamoDB Export diagram.
   - Fix visible issues before PR.

6. Repository validation
   - `xmllint --noout docs/images/readme-diagrams/*.svg`
   - README image-link / SVG-embed check.
   - `git diff --check`

7. Review artifacts and PR
   - Write `docs/review/2026-06-06-issue-491-implementation-review.md` with
     `P0=0`, `P1=0`.
   - Write a concise lesson under `docs/lessons/`.
   - Commit with Lore trailers.
   - Create a PR assigned to `debop`, linked to #491, milestone `0.4.0`, and
     verify the live PR body. The final PR body section must be `## DoD Status`.

## Stop Conditions

- Stop before PR if any mandatory diagram gate is missing or failing.
- Stop before PR if rendered PNG inspection finds unresolved overlap, cramped
  routing, 0-degree/tangent endpoint attachment, bad layer containment, or
  visibly imbalanced margins.
- Do not merge. Merge remains a separate user-requested step.

## Not Planned

- Gradle tests are not required unless the documentation changes reveal source
  drift or code edits become necessary.
- Existing Architecture and Sequence diagrams are not redrawn except for the
  missing DynamoDB Export set.
