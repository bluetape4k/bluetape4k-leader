# Issue #420 Consul Service Maintenance Example Plan

## Step 1: Baseline

- Worktree: `.worktrees/feat-issue-420-consul-service-maintenance-example`
- Branch: `feat/issue-420-consul-service-maintenance-example`
- Base: `origin/develop`
- Confirm `leader-consul` already exposes `ConsulEndpoint`,
  `ConsulLeaderElector`, and `ConsulLeaderElectionOptions`.
- Confirm integration tests use `ConsulServer.Launcher.consul`.

## Step 2: Example Module

- Add `examples/consul-maintenance/build.gradle.kts`.
- Add a runnable `ConsulMaintenanceDemo` entrypoint.
- Add `ServiceMaintenanceCoordinator` with a small report/status model.
- Add test resources required by new Kotlin example modules.

## Step 3: Tests

- Add `ServiceMaintenanceCoordinatorTest`.
- Use one held lock and one contender to prove the contender skips.
- Release the lock and prove the previous contender can perform maintenance.
- Keep tests serial-friendly with a single launcher-owned Consul container.

## Step 4: Registration

- Add `examples:consul-maintenance` to `settings.gradle.kts`.
- Add the example to `README.md`, `README.ko.md`, and repo-local `AGENTS.md`.
- Add CI path-filter output, filter, test job, and summary `needs`.
- Add the scheduled Examples workflow matrix entry.

## Step 5: Verification

- Run `./gradlew projects`.
- Run `./gradlew :examples:consul-maintenance:test`.
- Run `./gradlew :examples:consul-maintenance:run`.
- Run `actionlint .github/workflows/ci.yml .github/workflows/examples.yml`.
- Run `git diff --check`.
- Run a narrow production concurrency scan for the new module.

## Step 6: Review and PR

- Add a concise lesson entry.
- Review the diff against #420 acceptance criteria.
- Commit with Lore trailers.
- Push the branch and create a PR that closes #420, assigned to `debop`,
  milestone `0.3.0`, and relevant labels when available.
