---
manualId: "ktor-app"
id: "ktor-app"
title: "Ktor application workshop"
locale: "en"
kind: "example"
gradlePath: ":examples:ktor-app"
sourceDir: "examples/ktor-app"
releaseRef: "0.4.0"
artifact: null
---

# Ktor leader-scheduled application workshop

> Runnable release workshop · Lettuce Redis with Ktor `LeaderElectionPlugin` · source pinned to `0.4.0`

## Problem {#problem}

Several Ktor replicas serve HTTP concurrently, while a shared Redis lock allows one `leaderScheduled` aggregation per cycle. Non-leaders keep serving `/health`, `/readyz`, and `/stats`.

This is a coordination boundary, not a replacement for business idempotency. The exercise separates **who may start the body** from **how the body records durable progress**, so the result remains explainable after contention, timeout, or process loss.

## When to use it {#when-to-use}

Use this workshop when several Ktor replicas serve HTTP concurrently, while a shared Redis lock allows one `leaderScheduled` aggregation per cycle. Non-leaders keep serving `/health`, `/readyz`, and `/stats`. Start with the example unchanged, then replace only the workload while preserving the lock identity, bounded execution, and observable skip path.

Choose another pattern when every replica should perform the work, when a queue already assigns exclusive ownership, or when the operation cannot tolerate retry after a leader failure.

## Coordinates {#coordinates}

This example is not published as a library. Run it from the repository. In an application, choose one `bluetape4k-dependencies` version and declare the required leader backend without a second version:

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    // Add the backend used by this workshop without a version.
}
```

## Core concepts {#concepts}

- **Coordination backend:** Lettuce Redis with Ktor `LeaderElectionPlugin` stores or enforces the leadership decision.
- **Shared identity:** all contenders must derive the same lock name for the same logical work.
- **Skip-on-contention:** losing leadership is an expected result, not an exceptional failure.
- **Bounded ownership:** timeout, lease/session rules, and shutdown handling determine when another node may continue.
- **Workload safety:** retries, checkpoints, and idempotency belong to the application body.

Do not stop at a green build. Check this observable result: both servers stay healthy, but the shared aggregation run count increases once per cycle across replicas.

## Quick start {#quick-start}

Prerequisites: JDK 21+, Redis at `REDIS_URL`, and a free `PORT`.

```bash
REDIS_URL=redis://localhost:6379 ./gradlew :examples:ktor-app:run
```

Run two or more contenders when the demo supports it. Check this result: both servers stay healthy, but the shared aggregation run count increases once per cycle across replicas. Then stop or release the current owner and run the next cycle to verify takeover.

## API by task {#api-by-task}

1. Read `StatsAggregator` first; it defines the application-facing coordination boundary.
2. Follow `KtorAppMain` to see client construction, contender setup, and result reporting.
3. Read `KtorAppTest` to learn which ownership transitions the release promises.
4. Replace the demo workload with a small idempotent action before adapting a real job.

Treat the return value as part of the API. An executed, skipped, failed, or partially completed result should be logged and measured separately.

## Recommended patterns {#patterns}

- Namespace lock names by environment and workload, for example `prod:billing:nightly`.
- Keep the elected body small; move preparation before the lock and durable result recording inside the correct transaction boundary.
- Size wait time for fast contention feedback and ownership duration for the actual failure model.
- Preserve cancellation and interruption signals instead of converting them into a normal skip.
- Make takeover safe with an idempotency key, marker, claim token, or resumable checkpoint.

## Integrations {#integrations}

The workshop integrates Lettuce Redis with Ktor `LeaderElectionPlugin` with a runnable Gradle application. The example owns its local fixture for learning, but production code should inject the backend client and let the application lifecycle close it.

Scheduler, HTTP, operator, and coroutine frameworks should call the coordination boundary; they should not hide lock acquisition inside unrelated business services. That separation keeps contention and takeover visible in tests and metrics.

## Configuration {#configuration}

Review `REDIS_URL`, `PORT`, plugin lifecycle, lock name, schedule interval, wait/lease time, and application shutdown. Use the same logical lock identity on every replica and different identities for unrelated jobs or environments.

Start with short local values so contention and takeover are visible. Production values must come from measured body duration, backend latency, shutdown budget, and the cost of duplicate work—not from the demo defaults.

## Failure modes {#failures}

- **Every node executes:** lock names or namespaces differ. Log the final lock identity at startup.
- **No node executes:** backend reachability, credentials, RBAC, or an existing owner is blocking acquisition.
- **Takeover is late:** the lease/session or client timeout exceeds the service recovery budget.
- **Work repeats after failure:** leadership ended before durable progress was recorded; add an idempotency key or checkpoint.
- **Shutdown hangs:** the workload ignores cancellation or owns a client/executor that is not closed.

Diagnose in that order: backend health, resolved lock identity, acquisition result, body result, then release/takeover evidence.

## Operations {#operations}

Record attempts, acquired executions, skips, failures, body duration, and takeover latency by a bounded lock-name tag. Alert on sustained no-acquisition, growing body duration, or repeated ownership churn rather than on a normal isolated skip.

Document who owns backend provisioning, credentials, schema/keys, cleanup, and client shutdown. A successful demo does not transfer those responsibilities to the library.

## Testing {#testing}

```bash
./gradlew :examples:ktor-app:test
```

The important assertions are behavioral: exactly one owner during contention, a non-exceptional skip for contenders, release on normal and failed exits, and reacquisition by another node. If Docker or a privileged runtime is required, keep that integration test separate from fast unit tests but do not replace it with mocks.

## Workshops and learning path {#workshops}

This page is a guided workshop, not just a command reference. Work through it in five passes:

1. **Baseline:** run one contender and identify the workload boundary.
2. **Contention:** run multiple contenders and explain the executed and skipped results.
3. **Failure:** terminate or fail the owner and observe release or expiry.
4. **Takeover:** verify that another node continues without corrupting durable state.
5. **Adaptation:** change the lock namespace and workload, add metrics, and write one failure-focused test.

After this workshop, compare the backend manual with the core execution-model and failure-semantics chapters before moving the pattern into a service.

## Limitations {#limitations}

The in-memory stats endpoint is replica-local. Use shared storage or metrics when the aggregate result must be cluster-visible.

The demo favors a compact, observable scenario. It does not define production topology, credential rotation, capacity planning, disaster recovery, or a universal exactly-once guarantee.

## Sources {#sources}

- [Stable example README](../../../../examples/ktor-app/README.md)
- [Gradle build](../../../../examples/ktor-app/build.gradle.kts)
- [Main implementation](../../../../examples/ktor-app/src/main/kotlin/io/bluetape4k/leader/examples/ktor/StatsAggregator.kt)
- [Demo entry point](../../../../examples/ktor-app/src/main/kotlin/io/bluetape4k/leader/examples/ktor/KtorAppMain.kt)
- [Verification test](../../../../examples/ktor-app/src/test/kotlin/io/bluetape4k/leader/examples/ktor/KtorAppTest.kt)
