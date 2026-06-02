# bluetape4k leader examples

[한국어](README.ko.md) | English

This directory contains runnable examples for the bluetape4k leader-election backends and integration modules.
Each example focuses on one operational scenario: running one job across many replicas, gating rollout work,
coordinating control-plane loops, or exposing leader metrics.

## Example Map

| Example | Backend / storage | Scenario | Good fit | Command |
|---|---|---|---|---|
| [batch-scheduler](batch-scheduler/README.md) | Redis / Lettuce | Run one periodic batch job across many application replicas. | Cron, Quartz, or Spring scheduled jobs that must execute once. | `./gradlew :examples:batch-scheduler:run` |
| [cache-warmer](cache-warmer/README.md) | Hazelcast | Warm each cache partition once while multiple nodes compete. | Partitioned cache preloading with safe duplicate suppression. | `./gradlew :examples:cache-warmer:run` |
| [consul-maintenance](consul-maintenance/README.md) | Consul Session + KV | Let one service instance perform maintenance or drain work. | Service drain, endpoint rotation, or maintenance windows. | `./gradlew :examples:consul-maintenance:run` |
| [etcd-reconciler](etcd-reconciler/README.md) | etcd v3 | Let one control-plane node apply desired resources. | Reconciler loops and infrastructure controllers. | `./gradlew :examples:etcd-reconciler:run` |
| [k8s-lease](k8s-lease/README.md) | Kubernetes Lease | Exercise acquire, conflict, release, and reacquire behavior. | Low-level Lease API validation against K3s. | `./gradlew :examples:k8s-lease:k8sTest` |
| [k8s-operator](k8s-operator/README.md) | Kubernetes Lease + Spring Boot | Run one operator reconcile loop across several pods. | Kubernetes operators and controller loops. | `./gradlew :examples:k8s-operator:run` |
| [ktor-app](ktor-app/README.md) | Ktor + Redis / Lettuce | Run a leader-scheduled background aggregation job. | Ktor services with background tasks. | `./gradlew :examples:ktor-app:run` |
| [migration-gate](migration-gate/README.md) | Exposed JDBC | Gate schema migration during rolling deployment. | Startup migration and idempotent schema changes. | `./gradlew :examples:migration-gate:run` |
| [prometheus-dashboard](prometheus-dashboard/README.md) | Spring Boot + Lettuce + Micrometer | Expose leader AOP metrics and visualize them. | Observability for annotated leader jobs. | `./gradlew :examples:prometheus-dashboard:bootRun` |
| [rate-limiter](rate-limiter/README.md) | Redis / Lettuce + Bucket4j | Dispatch probes once and enforce shared quota. | Leader-dispatched work with distributed rate limits. | `./gradlew :examples:rate-limiter:run` |
| [redisson-watchdog](redisson-watchdog/README.md) | Redis / Redisson | Keep a long-running leader job alive with auto-extension. | Work that can exceed the initial lease time. | `./gradlew :examples:redisson-watchdog:run` |
| [strategic-election](strategic-election/README.md) | Local strategic election | Choose the best node by weighted score. | Backend-neutral candidate selection before running work. | `./gradlew :examples:strategic-election:run` |
| [tenant-aggregator](tenant-aggregator/README.md) | Exposed R2DBC | Run one coroutine aggregation loop per tenant. | Multi-tenant aggregation and async database workloads. | `./gradlew :examples:tenant-aggregator:run` |
| [virtual-thread-runner](virtual-thread-runner/README.md) | Local virtual-thread elector | Run blocking leader work on a Java virtual thread. | High-concurrency services with bounded blocking tasks. | `./gradlew :examples:virtual-thread-runner:run` |
| [webhook-poller](webhook-poller/README.md) | MongoDB | Elect one poller and claim webhook events atomically. | At-least-once webhook polling and event handlers. | `./gradlew :examples:webhook-poller:run` |

## Choosing An Example

- Start with the backend you already operate when the example is meant to mirror production storage.
- Use Redis / Lettuce or Redis / Redisson when a low-latency lock store is already part of the service stack.
- Use Exposed JDBC or R2DBC when leadership should live with application data and deployment migrations.
- Use Kubernetes Lease, etcd, or Consul when the leadership problem is tied to control-plane or service-discovery infrastructure.
- Use local strategic or virtual-thread examples to study API shape without starting external infrastructure.
- Use `prometheus-dashboard` when you need to inspect metrics emitted by Spring AOP leader-election annotations.

## Running

Most examples expose a Gradle `run` task and can also be launched from the IDE through the `mainClass` declared in
their `build.gradle.kts`. Infrastructure-backed examples use bluetape4k Testcontainers launchers, so Docker must be
available unless the README documents a separate external endpoint option.

```bash
./gradlew :examples:batch-scheduler:run
./gradlew :examples:consul-maintenance:test
./gradlew :examples:prometheus-dashboard:bootRun
```

Kubernetes examples need a local Docker daemon or CI runner that supports the K3s Testcontainers setup. The
`k8s-lease` example keeps its real-cluster check behind the `k8sTest` task.
