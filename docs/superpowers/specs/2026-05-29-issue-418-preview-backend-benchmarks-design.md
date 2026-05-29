# Issue 418 Preview Backend Benchmark Design

## Context

Issue #418 asks the non-published `benchmark` module to add comparable preview
backend rows for Consul, etcd, DynamoDB, and Kubernetes. Existing benchmark
rows already cover local, Redis/Lettuce, Redis/Redisson, Exposed H2, MongoDB,
Hazelcast, and ZooKeeper for blocking and suspend `runIfLeader` paths.

## Goal

Extend the existing `kotlinx-benchmark` harness so preview backends compile as
benchmark rows and perform a smoke `runIfLeader` check before timing. Update the
benchmark documentation only with fresh measured evidence or explicit caveats.
Run Kubernetes separately so the Fabric8 client runtime does not force Vert.x 4
onto the default preview backend classpath that needs Vert.x 5 for etcd.

## Scope

- Add benchmark dependencies for `leader-consul`, `leader-etcd`, and
  `leader-dynamodb` to the default benchmark source set.
- Add `leader-k8s` to a separate `kubernetesBenchmark` source set/runtime.
- Add blocking rows to `BackendLeaderElectorBenchmark`.
- Add suspend rows to `SuspendBackendLeaderElectorBenchmark`.
- Reuse existing Testcontainers launchers:
  - `ConsulServer.Launcher.consul`.
  - `EtcdServer.Launcher.etcd`.
  - `DynamoDbLocalServer.Launcher.dynamoDb`.
  - `K3sServer.Launcher.k3s`.
- Keep default benchmark source inside `benchmark/src/benchmark/kotlin`.
- Keep Kubernetes benchmark source inside
  `benchmark/src/kubernetesBenchmark/kotlin`.
- Keep timing benchmarks out of CI; CI only needs compile coverage.

## Backend Feasibility

| Backend | Blocking row | Suspend row | Feasibility |
|---|---:|---:|---|
| Consul | Yes | Yes | Uses existing Consul Testcontainers wrapper and endpoint-owned elector constructors. |
| etcd | Yes | Yes | Uses existing Etcd Testcontainers wrapper and jetcd client lifecycle. |
| DynamoDB | Yes | Yes | Uses DynamoDB Local wrapper; benchmark setup creates a fresh table with `lockName` hash key and TTL. |
| Kubernetes | Yes | Yes | Uses K3s wrapper in a separate benchmark target; row is useful but heavier than other rows and requires Docker/K3s-capable local execution. |

## Non-Goals

- No production API changes.
- No new benchmark framework or raw JMH-only setup.
- No README result-table update without fresh benchmark JSON from this branch.
- No chart regeneration unless fresh measured rows are available.

## Acceptance Criteria

- `:benchmark:compileBenchmarkKotlin` passes with preview rows.
- `:benchmark:compileKubernetesBenchmarkKotlin` passes with the K8s row on its
  separate runtime classpath.
- Implemented rows are included in `kotlinx-benchmark` parameter sets.
- Setup smoke checks fail fast when infrastructure is unavailable.
- README and localized README record command, environment caveat, raw result
  location, and interpretation after measurement.
- CI keeps compiling benchmark sources without running timing benchmarks.

## Step 2-R Local 7-Tier Review

| Tier | Scope | P0 | P1 | P2 | P3 | Evidence |
|---|---|---:|---:|---:|---:|---|
| Security | Local benchmark clients and endpoints | 0 | 0 | 0 | 0 | No secrets; Testcontainers credentials are local dummy values. |
| Ops/SRE | Testcontainers lifecycle and cleanup | 0 | 0 | 0 | 1 | K3s row is heavier and documented as Docker/K3s-capable only. |
| Structural | Module boundary and benchmark source sets | 0 | 0 | 0 | 0 | Changes stay in non-published `benchmark` module; K8s gets a separate source set. |
| Kotlin/API | Constructors and option contracts | 0 | 0 | 0 | 0 | `compileBenchmarkKotlin` verifies current APIs. |
| Tests/types | Compile and smoke behavior | 0 | 0 | 0 | 0 | Setup runs smoke `runIfLeader` before measurement. |
| Performance | Benchmark validity | 0 | 0 | 0 | 1 | Results are same-machine comparison only, not release-grade claims. |
| Docs/evidence | README and raw result policy | 0 | 0 | 0 | 0 | Result tables update only after fresh JSON exists. |

P0/P1: 0. Gate closed.
