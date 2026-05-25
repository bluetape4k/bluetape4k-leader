# Issue 378 README ERD Relationships

## Context

The root README has dense tables for modules, examples, and integration
surfaces. Issue 378 asked for an ERD-style visual summary that keeps tables as
the authoritative source while making relationships easier to scan.

## Decision

Convert the Examples table relationship into one shared README diagram:

- Module catalog rows publish backend and integration modules.
- Backend capability rows power runnable examples.
- Integration surface rows compose with examples when Ktor, Spring Boot, or
  Micrometer are part of the scenario.

This keeps a single SVG/PNG pair under `docs/images/readme-diagrams/` and embeds
the same PNG in both `README.md` and `README.ko.md` near the Examples table.

## Outcome

Added `readme-table-relationships-erd-01.svg` and the matching PNG. The diagram
summarizes Redis, SQL, MongoDB, Hazelcast, Kubernetes, Ktor, Spring Boot, and
Micrometer relationships without replacing the detailed README tables.

## Verification

- `xmllint --noout docs/images/readme-diagrams/*erd*.svg`
- `git diff --check`
- `rg -n 'erd|ERD|readme-diagrams/.*\.png' README.md README.ko.md`
- Checked diagram labels against `README.md`, `settings.gradle.kts`, and
  `examples/` for current module/example names.
- Rendered PNG with `rsvg-convert` and visually inspected the result.
- Worktree audit artifacts:
  - `.omx/artifacts/issue-378-audit-readme-diagrams-worktree.log`
  - `.omx/artifacts/issue-378-audit-readme-diagram-quality-worktree.log`

The global audit scripts still exit with existing unrelated findings in older
non-ERD diagrams, but the new ERD asset has no worktree audit findings.

## Future Guidance

When the Examples table changes, update this ERD by validating three directions:

1. Every diagram example still exists under `examples/`.
2. Every diagram module/backend name still appears in the module table or
   `settings.gradle.kts`.
3. Relationship arrows remain one-way from module catalog to capability or
   integration surface, and then to runnable examples.
