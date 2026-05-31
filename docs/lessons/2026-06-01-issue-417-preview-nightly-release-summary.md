# Issue 417 Preview Nightly Release Summary

## Context

Preview backend release evidence was spread across Consul, DynamoDB, etcd, and
Kubernetes Nightly jobs, which made release preflight review easy to miss.

## Decision

Add a dedicated Nightly summary job that writes the preview backend release
gate table to the GitHub Actions step summary. Keep heavy DynamoDB, etcd, and
K3s checks in full Nightly, and treat skipped results as a signal to run full
Nightly before tagging.

## Outcome

Release reviewers now have one compact summary for preview backend readiness
without changing daily CI scope.

## Verification

- `git diff --check`
- `actionlint .github/workflows/nightly-tests.yml`

## Future Guidance

When adding another preview backend, add its full-runtime job to the summary job
and to the release gate note in `docs/release/preview-backend-nightly-gate.md`.
