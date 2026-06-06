# Issue #491 Example Scenario And Flow Diagrams

## Context

Issue #491 required the example README set to be easier to scan by adding or normalizing Scenario and Flow diagrams, while keeping README embeds as PNG and preserving matching SVG/DOT/plain/Graphviz evidence.

## Decision

- Use one generator, `scripts/generate-example-flow-diagrams.mjs`, as the source of truth for example Scenario, Flow, DynamoDB Architecture, and DynamoDB Sequence assets.
- Keep generated diagram labels in English for shared reuse across `README.md` and `README.ko.md`.
- Use semantic route colors for leader/success, skip/failure, contention/release, and retry/next-run paths, with legends in every generated diagram.
- Process only new target SVGs through the evidence regeneration script during write mode to avoid unrelated existing diagram churn.

## Outcome

- Non-ZooKeeper examples now have Scenario, Architecture, Flow, and Sequence sections in both English and Korean README files.
- DynamoDB export now has the full diagram set instead of prose-only documentation.
- ZooKeeper Scheduler kept its existing four-diagram set without asset churn.

## Verification

- `node scripts/generate-example-flow-diagrams.mjs`
- `node scripts/regenerate-readme-diagram-graphviz-evidence.mjs --check` -> `diagrams=99 failures=0`
- `find docs/images/readme-diagrams -maxdepth 1 -name '*.svg' -print0 | xargs -0 xmllint --noout`
- README image-link check -> `readmes=36 pngEmbeds=136 svgEmbeds=0 missing=0`
- `rg -n -F '.svg)' examples -g 'README*.md'` -> no matches
- `git diff --check`
- Visual QA via `.omx/artifacts/issue-491-example-scenario-flow-contact-sheet.png` plus individual Scenario/DynamoDB PNG inspections.

## Future Guidance

- Do not hand-edit generated README diagrams. Change the generator, rerender only the affected targets, then run the global evidence check.
- If a write-mode evidence run touches unrelated existing assets, restore that churn before PR creation and rerun `--check`.
