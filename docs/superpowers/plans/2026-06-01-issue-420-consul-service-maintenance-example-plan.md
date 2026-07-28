# Issue #420 Consul Service Maintenance Example Plan

## 한국어 해설

이 문서는 `Issue #420 Consul Service Maintenance Example Plan`에 대한 설계 또는 실행 계획 기록입니다. 아래 원문 구조의 범위, 결정, 작업 순서, 검증 조건, 위험 및 후속 조치는 기록 보존을 위해 유지합니다. 검토자는 각 `Action`, `Expected DoD`, `Validation`, `Target files` 항목을 한국어 해설과 함께 읽고, 코드 식별자와 명령은 원문 그대로 취급해야 합니다.



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
