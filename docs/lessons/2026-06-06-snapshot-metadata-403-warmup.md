# 2026-06-06 - Snapshot Metadata 403 Warm-Up

## Context

The manually dispatched Examples workflow for issue #497 proved that both K8s
example jobs passed, but `examples-tenant-aggregator` failed on Central
SNAPSHOT metadata with HTTP 403 for
`bluetape4k-exposed-r2dbc-tests:1.11.0-SNAPSHOT`. A failed job retry passed,
confirming a transient metadata lookup problem rather than a test regression.

## Decision

Move Central SNAPSHOT retry handling into a dedicated warm-up script that
retries only metadata-resolution failures. Expand CI, Examples, and Nightly
warm-up scopes to cover exposed test fixtures as well as the existing Ktor
SNAPSHOT consumers.

## Outcome

Ktor and exposed SNAPSHOT coordinates are warmed before dependent tests run.
When Central returns metadata 403, the retry happens in the warm-up step instead
of rerunning an already-started test job.

## Verification

- `bash -n .github/scripts/retry-snapshot-warmup.sh`
- `actionlint .github/workflows/ci.yml .github/workflows/examples.yml .github/workflows/nightly-tests.yml`
- `git diff --check`
- GitHub Examples run 27055610835 passed after rerunning the failed
  `examples-tenant-aggregator` job; the first failure was Central metadata 403,
  while both K8s jobs passed before the retry.

## Future Guidance

When adding a test module that consumes bluetape4k SNAPSHOT artifacts, add its
`compileTestKotlin` task to the relevant warm-up scope before relying on the
test matrix retry loop.
