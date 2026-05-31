# Issue 426 Virtual Thread Runner Example

## Context

The library exposed virtual-thread electors but had no runnable example showing
how users should apply them for high-concurrency leader-only work.

## Decision

Add a local-backend example that races many nodes against one lock, proves only
one action runs, and asserts that the elected action runs on a Java virtual
thread. Keep the example infrastructure-free so it is safe for release-branch CI.

## Outcome

`examples/virtual-thread-runner` now documents when to use virtual-thread
electors versus coroutine or blocking electors and is wired into CI and the
Examples workflow.

## Verification

- `./gradlew :examples:virtual-thread-runner:test --no-daemon`
- `actionlint .github/workflows/ci.yml .github/workflows/examples.yml`
- `git diff --check`

## Future Guidance

Use local virtual-thread examples for API-shape education. Use backend-specific
examples only when the scenario needs external runtime semantics.
