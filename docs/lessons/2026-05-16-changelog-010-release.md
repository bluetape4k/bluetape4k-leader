# Lesson: CHANGELOG restructuring for 0.1.0 release

**Date**: 2026-05-16
**Issue**: #266
**PR**: TBD

## Root Cause

The repository had a large `[Unreleased]` section and a separate `[0.1.0-SNAPSHOT] — In Progress`
section that accumulated throughout the development cycle. Before the 0.1.0 release these needed
to be merged, grouped by change type, and converted to a versioned entry with a release date.

## Actions Taken

1. Merged `[Unreleased]` + `[0.1.0-SNAPSHOT]` into `## [0.1.0] — 2026-05-16`.
2. Grouped entries into Keep-a-Changelog sections:
   - **Breaking Changes** — renamed interfaces, `runIfLeader()` semantics, Duration migration,
     Spring Boot module consolidation, `LeaderElectionEvent.Elected` field additions
   - **Added** — all new modules, interfaces, implementations, CI/CD, and examples
   - **Changed** — behavior changes that are not breaking
   - **Fixed** — bug fixes
   - **Removed** — 10 deprecated APIs removed by #264
3. Added empty `## [Unreleased]` section at the top for post-release work.
4. Updated comparison links to point to `v0.1.0` instead of `v0.1.0-SNAPSHOT`.
5. Removed the `### Open Follow-ups` subsection (tracked in GitHub issues #73, #77, #79, etc.).

## Future Guidance

- Keep entries under `[Unreleased]` current during development — don't let them accumulate
  without section headers.
- Every merged PR should add at least one bullet under the appropriate section.
- Breaking changes always get their own `### Breaking Changes` subsection with a migration snippet.
- The `### Removed` section should reference the issue that drove the removal.
