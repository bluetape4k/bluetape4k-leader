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
- After PR CI exposed transient Central snapshots metadata 403 responses, the
  Gradle changing-module cache TTL was relaxed from zero seconds to one day so
  regular PR CI does not revalidate bluetape4k SNAPSHOT metadata on every
  configuration.
- PR CI Gradle invocations no longer pass `--refresh-dependencies`; Nightly
  keeps explicit refreshes for SNAPSHOT freshness checks.

## Verification

- `node scripts/generate-example-flow-diagrams.mjs`
- `node scripts/regenerate-readme-diagram-graphviz-evidence.mjs --check` -> `diagrams=99 failures=0`
- `find docs/images/readme-diagrams -maxdepth 1 -name '*.svg' -print0 | xargs -0 xmllint --noout`
- README image-link check -> `readmes=36 pngEmbeds=136 svgEmbeds=0 missing=0`
- `rg -n -F '.svg)' examples -g 'README*.md'` -> no matches
- `git diff --check`
- `./gradlew help --no-daemon`
- `actionlint .github/workflows/ci.yml`
- `rg -n -- '--refresh-dependencies' .github/workflows/ci.yml` -> no matches
- Visual QA via `.omx/artifacts/issue-491-example-scenario-flow-contact-sheet.png` plus individual Scenario/DynamoDB PNG inspections.
- PR CI rerun after the cache TTL change.

## Future Guidance

- Do not hand-edit generated README diagrams. Change the generator, rerender only the affected targets, then run the global evidence check.
- If a write-mode evidence run touches unrelated existing assets, restore that churn before PR creation and rerun `--check`.
- Keep ordinary PR CI on the Gradle default-style changing-module cache TTL.
  Use `--refresh-dependencies` only for explicit SNAPSHOT freshness checks after
  publishing, because zero-second SNAPSHOT metadata refresh amplifies transient
  Central snapshots 403 failures across matrix jobs.
