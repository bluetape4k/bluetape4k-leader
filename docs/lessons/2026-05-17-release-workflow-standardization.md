# Release Workflow Standardization

Context: The Central Portal release campaign uses `bluetape4k-projects` as the
canonical release workflow shape.

Decision: Rename the Nightly workflow file to `nightly-tests.yml` while keeping
the workflow display name as `Nightly`, and update agent guidance references.

Outcome: Release preparation scripts can rely on the same workflow file names
across bluetape4k repositories.

Verification: `actionlint .github/workflows/nightly-tests.yml .github/workflows/publish-snapshot.yml .github/workflows/release.yml`.

Future guard: Keep release workflow file names aligned with `bluetape4k-projects`
unless a repo-specific exception is documented in `AGENTS.md`.
