# Issue #420 Consul Maintenance Example

## Context

Milestone `0.3.0` needed a runnable Consul example showing leader-only service
maintenance/drain behavior.

## Decision

Use `ConsulLeaderElector` with caller-owned `ConsulEndpoint` and the existing
`ConsulServer.Launcher.consul` Testcontainers helper. Keep the example as a
plain application module instead of introducing a Spring/Ktor surface.

## Outcome

Added `examples/consul-maintenance`, README locale pair, root README entries,
repo-local module list, Gradle settings registration, CI path filter/job, and
Examples workflow matrix coverage.

## Verification

- `./gradlew projects`
- `./gradlew :examples:consul-maintenance:test`
- `./gradlew :examples:consul-maintenance:run`
- `actionlint .github/workflows/ci.yml .github/workflows/examples.yml`
- `git diff --check`
- `rg -n "@Synchronized|synchronized\\s*\\(" examples/consul-maintenance/src/main`

## Future Guidance

For backend-specific example modules, keep the runnable scenario small and prove
contention with a held lock plus a skipped contender. Always wire new examples
through settings, README locale pair, repo-local AGENTS, CI, and Examples
workflow in the same PR.
