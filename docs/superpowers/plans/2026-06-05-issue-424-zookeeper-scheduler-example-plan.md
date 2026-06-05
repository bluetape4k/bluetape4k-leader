# Issue #424 ZooKeeper legacy scheduler example plan

> Date: 2026-06-05 KST
> Issue: #424 `example(zookeeper): add Curator-backed legacy scheduler example`
> Spec: `docs/superpowers/specs/2026-06-05-issue-424-zookeeper-scheduler-example-design.md`
> Prerequisite gate: Step 2-R passed with P0=0 and P1=0.

## Plan

1. Create the `examples/zookeeper-scheduler` module skeleton.
   - Add `build.gradle.kts` with the `application` plugin.
   - Set main class to `io.bluetape4k.leader.examples.zookeeperscheduler.ZooKeeperSchedulerDemo`.
   - Depend on `:bluetape4k-leader-zookeeper`, `bluetape4k-core`, `bluetape4k-logging`, `bluetape4k-testcontainers`, `bluetape4k-junit5`, and Logback.
   - Add `junit-platform.properties`, `logback-test.xml`, and demo `logback.xml`.

2. Implement scheduler domain classes.
   - Add `ZooKeeperLegacyScheduler`.
   - Add serializable value objects for node id, lock name, and schedule id with `requireNotBlank`.
   - Add serializable report/status models.
   - Use `ZooKeeperLeaderElector` and `LeaderElectionOptions`.
   - Do not use direct Curator lock recipes in example code.
   - Use `Base58.randomString` for unique demo/test identifiers.

3. Implement the runnable demo.
   - Start ZooKeeper through `ZooKeeperServer.Launcher`.
   - Create a Curator client through `ZooKeeperServer.Launcher.getCuratorFramework(server)`.
   - Start, connect, and close the Curator client in caller-owned lifecycle scope.
   - Show node-a executing while node-b skips during contention.
   - Show node-b executing the next schedule after release.
   - Keep runtime logs concise with example-local Logback config.

4. Add Testcontainers integration tests.
   - Use `ZooKeeperServer.Launcher` and a started Curator client in a base test.
   - Verify single execution.
   - Verify skip-on-contention with one held lock and a competing scheduler.
   - Verify release/reacquire across two schedule ids.
   - Verify blank node id, lock name, schedule id, and job step are rejected.
   - Use `bluetape4k-assertions` and avoid raw JUnit assertions in new test code.

5. Register documentation and module metadata.
   - Add `settings.gradle.kts` include.
   - Update root `README.md` and `README.ko.md`.
   - Update `examples/README.md` and `examples/README.ko.md`.
   - Update repo-local `AGENTS.md` example lists.
   - Add `examples/zookeeper-scheduler/README.md` and `README.ko.md`.

6. Register CI and examples workflow coverage.
   - Add `examples-zookeeper-scheduler` change output and path filter in `.github/workflows/ci.yml`.
   - Add dedicated `test-examples-zookeeper-scheduler` job.
   - Add the job to `coverage-report` and `ci-status` `needs`.
   - Add weekly `.github/workflows/examples.yml` matrix entry.

7. Run validation serially.
   - `./gradlew :examples:zookeeper-scheduler:compileKotlin --no-daemon --no-configuration-cache`
   - `./gradlew :examples:zookeeper-scheduler:test --no-daemon --no-configuration-cache`
   - `./gradlew :examples:zookeeper-scheduler:run --no-daemon --no-configuration-cache`
   - `./gradlew projects --no-daemon --no-configuration-cache`
   - `actionlint .github/workflows/ci.yml .github/workflows/examples.yml`
   - `git diff --check`

8. Run delivery gates.
   - Step 6-R local 7-tier code review on the implementation diff.
   - Require `P0=0` and `P1=0` before PR creation.
   - Add `docs/lessons/2026-06-05-issue-424-zookeeper-scheduler-example.md`.
   - Commit with Lore protocol.
   - Push branch.
   - Create PR with `--body-file`.
   - Verify live PR body and ensure the final `##` heading is `## DoD Status`.
   - Add PR review comment and formal review entry when GitHub accepts it.
   - Watch CI and report result.

## Reuse Decisions

- Reuse `ZooKeeperLeaderElector`; reject direct Curator lock recipes in the example.
- Reuse `ZooKeeperServer.Launcher`; reject raw `GenericContainer`.
- Reuse `Base58.randomString`, `requireNotBlank`, bluetape4k logging, bluetape4k assertions, and existing example module conventions.
- Reuse registration patterns from `dynamodb-export` and `consul-maintenance`.

## Stop Condition

Stop after PR creation, live PR body verification, PR review gate, and CI result reporting. Do not merge unless the user explicitly requests merge.
