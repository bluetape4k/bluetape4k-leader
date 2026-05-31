# Issue #420 Consul Service Maintenance Example Design

## Context

Issue #420 asks for a runnable Consul-backed service maintenance example for
milestone `0.3.0`. The example must show how multiple service instances
coordinate a maintenance or drain action through Consul Session + KV leader
election, while preserving the existing example-module registration contract.

## Goals

- Add a new `examples/consul-maintenance` application module.
- Demonstrate that exactly one service instance performs a maintenance action
  while contenders skip.
- Use `ConsulLeaderElector` with `ConsulEndpoint` and
  `ConsulLeaderElectionOptions`; do not expose internal Consul lock client APIs
  in the example.
- Use the existing `ConsulServer.Launcher.consul` Testcontainers helper.
- Provide English and Korean READMEs for the example.
- Register the module in Gradle settings, root README locale pair, repo-local
  AGENTS module list, CI path filters/jobs, and the scheduled Examples workflow.

## Non-Goals

- No new public library API.
- No Spring Boot or Ktor integration layer for this example.
- No raw Testcontainers `GenericContainer` usage.
- No publishing or BOM registration changes because `examples/*` are excluded
  from publishing by path prefix.

## Design

The module models a fleet of service instances competing for the same
maintenance lock. `ServiceMaintenanceCoordinator` owns a node identity, Consul
endpoint, lock name, and key prefix. It uses `ConsulLeaderElector.runIfLeader`
to run one maintenance supplier only when the node acquires the Consul Session
and KV lock.

The result is a small serializable report:

- `MaintenanceStatus.PERFORMED` when the node was elected and returned tasks.
- `MaintenanceStatus.SKIPPED` when another node already holds leadership.

The runnable demo starts the managed Consul container with
`ConsulServer.Launcher.consul`, creates three coordinators against one lock, and
prints the selected maintenance node and skipped nodes.

## Acceptance Criteria

- A contention test proves one node holds the lock while another skips, then the
  skipped node can acquire after release.
- `./gradlew :examples:consul-maintenance:test` passes with Testcontainers.
- `./gradlew :examples:consul-maintenance:run` prints one performed report.
- `./gradlew projects` lists `:examples:consul-maintenance`.
- CI and Examples workflows include the module path and test task.
- `README.md` and `README.ko.md` list the new example.
