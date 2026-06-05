# Issue #424 ZooKeeper legacy scheduler example design

> Date: 2026-06-05 KST
> Issue: #424 `example(zookeeper): add Curator-backed legacy scheduler example`
> Milestone: 0.4.0
> Workflow: Type A Full Feature

## Context

`leader-zookeeper` is documented as a stable backend, but `examples/` has no runnable ZooKeeper scenario. Users who already operate ZooKeeper or Curator need a concrete migration-style example showing how a legacy scheduled job can be guarded so only one application instance executes it.

The example must actively use the bluetape4k ecosystem, not merely wrap third-party Curator code:

- `ZooKeeperLeaderElector` and `LeaderElectionOptions` provide the election boundary.
- `ZooKeeperServer.Launcher` provides local Testcontainers infrastructure.
- `Base58.randomString` provides deterministic-safe unique test/demo names.
- `requireNotBlank` validates caller inputs.
- `bluetape4k-assertions`, `runSuspendIO`, and existing test resource conventions validate behavior.

## Scope

Add `examples/zookeeper-scheduler`, a runnable application module demonstrating one ZooKeeper-elected scheduler runner.

The module will include:

- A coordinator/runner that accepts a legacy scheduled job supplier and executes it only when ZooKeeper leadership is acquired.
- Serializable value objects and reports for node identity, lock name, schedule id, execution status, and completed job steps.
- A demo `main` that starts ZooKeeper through bluetape4k Testcontainers, creates two competing nodes, shows one skipped execution during contention, and shows the other node executing after release.
- Integration tests with ZooKeeper Testcontainers verifying single execution, skip-on-contention, release/reacquire, and input validation.
- English and Korean README files.
- Registration in `settings.gradle.kts`, root README locale files, `examples/README.md`, `examples/README.ko.md`, `AGENTS.md`, `.github/workflows/ci.yml`, and `.github/workflows/examples.yml`.

## Non-Goals

- No new `leader-zookeeper` public API.
- No production ZooKeeper ACL/TLS setup.
- No Spring scheduler or Quartz dependency.
- No publishing/BOM changes because `examples/*` modules are excluded by path-prefix rules.

## Design

### Module

Gradle path: `:examples:zookeeper-scheduler`

Main class:

`io.bluetape4k.leader.examples.zookeeperscheduler.ZooKeeperSchedulerDemo`

Core class:

`ZooKeeperLegacyScheduler`

The scheduler owns a `ZooKeeperLeaderElector` built from a caller-owned Curator client and `LeaderElectionOptions`. It exposes:

```kotlin
fun runOnce(scheduleId: SchedulerRunId, job: () -> List<String>): SchedulerRunReport
```

Contract:

- If leadership is acquired, execute `job`, validate returned step names, and return `SchedulerRunStatus.EXECUTED`.
- If leadership is not acquired before `waitTime`, do not execute `job` and return `SchedulerRunStatus.SKIPPED`.
- Release leadership after the job completes.
- Preserve the `runIfLeader` skip-on-contention contract: normal contention is not an exception.

### ZooKeeper Semantics

ZooKeeper locks are session-based, not TTL-based. The example will keep `autoExtend=false` and explain that session keepalive owns lock liveness. This mirrors `leader-zookeeper` behavior and avoids teaching users to rely on TTL renewal in ZooKeeper.

### Resource Lifecycle

The demo and tests create a Curator client with `ZooKeeperServer.Launcher.getCuratorFramework(server)`, call `start()`, wait for connection, and close the client after use. The example scheduler does not close the Curator client because ownership stays with the caller.

### Documentation

README files must describe:

- What the example proves.
- How ZooKeeper differs from TTL-based backends.
- How to run `./gradlew :examples:zookeeper-scheduler:run` and `:examples:zookeeper-scheduler:test`.
- That Docker/Testcontainers is required for local execution.

## Acceptance Criteria

- `examples/zookeeper-scheduler` compiles and runs locally.
- Tests verify only one competing node executes while another holds the lock.
- Tests verify the lock can be reacquired after release.
- Tests verify blank node id, lock name, and schedule id are rejected.
- The example uses bluetape4k ecosystem helpers listed in Context.
- Root README, examples README, repo-local AGENTS, CI, and examples workflow registration are synchronized.
- `./gradlew projects` lists `:examples:zookeeper-scheduler`.
- `actionlint .github/workflows/ci.yml .github/workflows/examples.yml` passes.

## Risks

- Testcontainers ZooKeeper can be slow or environment-sensitive; run these tests serially and use existing singleton launcher patterns.
- If the demo uses only sequential calls, it would not prove contention. The demo must include a held leader lock plus a second node skip.
- Direct Curator recipe code in the example would underuse bluetape4k; implementation must keep the user-facing coordination API on `ZooKeeperLeaderElector`.
