# AGENTS.md - bluetape4k-leader

Distributed leader election library with blocking, async, coroutine, and
virtual-thread APIs. Backends include Redis/Lettuce, Redis/Redisson, Exposed,
MongoDB, DynamoDB, etcd, Consul, Kubernetes, Hazelcast, and ZooKeeper. Ktor 3.x
integration is provided by `bluetape4k-leader-ktor`.

- Group: `io.github.bluetape4k.leader`
- Publishing: Maven Central through NMCP (`examples/*` are excluded — see
  Publishing rules below)

## Layout

```text
bluetape4k-leader-bom/
leader-core/
leader-redis-lettuce/
leader-redis-redisson/
leader-exposed-core/
leader-exposed-jdbc/
leader-exposed-r2dbc/
leader-mongodb/
leader-hazelcast/
leader-zookeeper/
leader-etcd/
leader-consul/
leader-dynamodb/
leader-k8s/
leader-micrometer/
leader-spring-boot/
leader-ktor/
examples/
  batch-scheduler/        # Lettuce Redis — periodic batch single execution
  migration-gate/         # Exposed JDBC — boot-time schema migration gate
  webhook-poller/         # MongoDB — single-instance webhook polling
  cache-warmer/           # Hazelcast — per-partition leader cache warming
  tenant-aggregator/      # Exposed R2DBC — coroutine multi-tenant aggregation
  ktor-app/               # Ktor 3.x + Lettuce Redis — leaderScheduled() demo
  prometheus-dashboard/   # Spring Boot + Prometheus/Grafana leader metrics demo
  etcd-reconciler/        # etcd — one control-plane node runs reconcile loop
  consul-maintenance/     # Consul — one service instance runs maintenance/drain
  dynamodb-export/        # DynamoDB Local — one node writes scheduled exports
  k8s-lease/              # Kubernetes Lease — low-level acquire/release demo
  k8s-operator/           # Kubernetes Lease + Spring Boot — operator loop demo
  rate-limiter/           # Lettuce Redis + Bucket4j — leader-dispatched probes
  strategic-election/     # Local strategic election — weighted scoring demo
buildSrc/
```

## Commands

```bash
./gradlew clean build
./gradlew build -x test
./gradlew :bluetape4k-leader-core:build
./gradlew :bluetape4k-leader-redis-lettuce:test
./gradlew :bluetape4k-leader-redis-redisson:test
./gradlew test --tests "io.bluetape4k.leader.redisson.RedissonLeaderElectionTest"
./gradlew :bluetape4k-leader-spring-boot:test
./gradlew detekt
./gradlew publishBluetape4kLeaderPublicationToBluetape4kLeaderRepository
./gradlew publishBluetape4kLeaderPublicationToBluetape4kLeaderRepository -PsnapshotVersion=
```

## Core Contract

`runIfLeader()` must not throw for normal lock contention. If leader election
fails because the lock was not acquired, return `null`, matching ShedLock-style
skip-on-contention behavior.

```kotlin
val result = leaderElection.runIfLeader("job-lock") { doWork() }
// result is doWork() output when elected, or null when skipped
```

## Interfaces

| Interface | Execution model |
|---|---|
| `LeaderElector` | Blocking |
| `AsyncLeaderElector` | `CompletableFuture` async |
| `VirtualThreadLeaderElector` | Virtual thread per election |
| `SuspendLeaderElector` | Coroutine suspend |
| `LeaderGroupElector` | Blocking semaphore-style multi-leader |
| `SuspendLeaderGroupElector` | Coroutine semaphore-style multi-leader |

## Spring AOP Rules

`@LeaderElection` and `@LeaderGroupElection` use AspectJ compile-time weaving
through Freefair post-compile weaving.

- Do not add `@EnableAspectJAutoProxy`; CTW handles weaving.
- Kotlin methods do not need to be `open`.
- Private methods are not intercepted; startup validation warns or fails.
- `@LeaderElection` supports `T?`, `suspend T?`, `Mono<T>`, `Flux<T>`,
  and Kotlin `Flow<T>`. Use `autoExtend = true` for long-running streams or
  `streamBounded = true` only when the stream finishes inside the lease window.
- `@LeaderGroupElection` supports `T?`, `suspend T?`, and `Mono<T>`.
  `Flux<T>` and Kotlin `Flow<T>` group streams are rejected because per-slot
  stream lease extension is not defined.
- SpEL names must be valid expressions. Use `"'prefix-' + #param"`, not
  `"prefix-#param"`.
- Strict validation throws for final/private/suspend footguns. Invalid SpEL with
  `maxLeaders <= 1` always fails.

Auto-configuration order:

```text
LeaderElectionAutoConfiguration
LeaderAopFactoryAutoConfiguration
LeaderMicrometerAutoConfiguration
LeaderAopAutoConfiguration
```

## Publishing Rules

Examples are not publishing artifacts. The root `build.gradle.kts` excludes
every project under `:examples:` from `publishing`, the NMCP aggregation, and
the publishing-signing tasks:

```kotlin
// build.gradle.kts (root)
subprojects {
    if (path.startsWith(":examples:")) {
        // skip publishing/signing/NMCP for examples
    }
}
// NMCP aggregation
subprojects
    .filter { !it.path.startsWith(":examples:") }
// signing
subprojects
    .filter { it.name != "bluetape4k-leader-bom" && !it.path.startsWith(":examples:") }
```

When adding a new module under `examples/`, no publishing change is required —
the path-prefix filter handles it. Library modules (`leader-*`) must be
registered in `bluetape4k-leader-bom/build.gradle.kts` constraints.

## CI / Scheduled Workflow Update Checklist

When adding (or renaming) a module — library or example — keep the four
locations below in sync. Missing any of them silently disables coverage.

1. `settings.gradle.kts` — `include(...)` entry.
2. `.github/workflows/ci.yml`
   - `paths-filter` filter outputs (changes job)
   - per-module test job (`test-<module>`)
   - aggregator `needs:` lists (build/test summary)
3. Scheduled workflow:
   - publishable `leader-*` modules: `.github/workflows/nightly-tests.yml`
   - `examples/*` modules: `.github/workflows/examples.yml`
   - add the per-module/matrix test entry and aggregator `needs:` when needed
4. `bluetape4k-leader-bom/build.gradle.kts` — only for publishable `leader-*` modules
   (NOT for `examples/*`).

Example modules currently wired into `ci.yml` and `examples.yml`:
`batch-scheduler`, `migration-gate`, `webhook-poller`, `cache-warmer`,
`tenant-aggregator`, `ktor-app`, `prometheus-dashboard`, `k8s-lease`,
`etcd-reconciler`, `consul-maintenance`, `dynamodb-export`, `k8s-operator`,
`rate-limiter`. The library module `bluetape4k-leader-ktor` remains in `ci.yml` and
`nightly-tests.yml` (Testcontainers Redis).

## Codex Spec / Plan / Code Review Stages

For non-trivial changes (new module, public API change, cross-module refactor)
delegate each phase to Codex with retry-3 to absorb transient failures:

1. **Spec** — `codex run spec --retry 3` to draft scope, contracts, and
   acceptance criteria before any code change.
2. **Plan** — `codex run plan --retry 3` to break the spec into ordered tasks.
3. **Implementation** — author the change directly, keeping each commit small
   and aligned with the plan tasks.
4. **Code Review** — `codex run review --retry 3` against the diff to surface
   regressions, then fix HIGH/CRITICAL findings before requesting human review.

Trivial documentation-only or single-file fixes can skip Spec/Plan but should
still run the Code Review pass before merging.

## Cross-Repo Lesson Guards

- Before issue, PR, workflow, release, or module-registration work, query GNO
  for this repo in both `bluetape4k-github` and `bluetape4k-docs`.
- For leader modules and examples, keep `settings.gradle.kts`, README locale
  sets, repo-local module lists, CI path filters/jobs, Nightly or examples
  workflow coverage, aggregator `needs`, coverage artifacts, and BOM constraints
  synchronized.
- For distributed-lock or leader-election changes, verify sync, async, suspend,
  and virtual-thread paths for delegate/watchdog parity. Run Redis and other
  Testcontainers-backed checks sequentially.
