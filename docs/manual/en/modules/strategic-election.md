---
manualId: "strategic-election"
id: "strategic-election"
title: "Strategic election workshop"
locale: "en"
kind: "example"
gradlePath: ":examples:strategic-election"
sourceDir: "examples/strategic-election"
releaseRef: "0.4.0"
artifact: null
---

# Strategic candidate selection workshop

> Runnable release workshop · backend-neutral local strategic election · source pinned to `0.4.0`

## Problem {#problem}

The first node to reach a lock is not always the best leader. This example combines readiness, success rate, and idle time with weighted scorers before running the winner's body.

This is a coordination boundary, not a replacement for business idempotency. The exercise separates **who may start the body** from **how the body records durable progress**, so the result remains explainable after contention, timeout, or process loss.

## When to use it {#when-to-use}

Use this workshop when the first node to reach a lock is not always the best leader. This example combines readiness, success rate, and idle time with weighted scorers before running the winner's body. Start with the example unchanged, then replace only the workload while preserving the lock identity, bounded execution, and observable skip path.

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

- **Coordination backend:** backend-neutral local strategic election stores or enforces the leadership decision.
- **Shared identity:** all contenders must derive the same lock name for the same logical work.
- **Skip-on-contention:** losing leadership is an expected result, not an exceptional failure.
- **Bounded ownership:** timeout, lease/session rules, and shutdown handling determine when another node may continue.
- **Workload safety:** retries, checkpoints, and idempotency belong to the application body.

Do not stop at a green build. Check this observable result: normalized scores explain the winner and non-winners return a skip result without running the action.

## Quick start {#quick-start}

Prerequisites: JDK 21+ and Docker when the backend test uses Testcontainers.

```bash
./gradlew :examples:strategic-election:run
```

Run two or more contenders when the demo supports it. Check this result: normalized scores explain the winner and non-winners return a skip result without running the action. Then stop or release the current owner and run the next cycle to verify takeover.

## API by task {#api-by-task}

1. Read `StrategicElectionDemo` first; it defines the application-facing coordination boundary.
2. Follow `StrategicElectionDemo` to see client construction, contender setup, and result reporting.
3. Read `StrategicElectionDemoTest` to learn which ownership transitions the release promises.
4. Replace the demo workload with a small idempotent action before adapting a real job.

Treat the return value as part of the API. An executed, skipped, failed, or partially completed result should be logged and measured separately.

## Recommended patterns {#patterns}

- Namespace lock names by environment and workload, for example `prod:billing:nightly`.
- Keep the elected body small; move preparation before the lock and durable result recording inside the correct transaction boundary.
- Size wait time for fast contention feedback and ownership duration for the actual failure model.
- Preserve cancellation and interruption signals instead of converting them into a normal skip.
- Make takeover safe with an idempotency key, marker, claim token, or resumable checkpoint.

## Integrations {#integrations}

The workshop integrates backend-neutral local strategic election with a runnable Gradle application. The example owns its local fixture for learning, but production code should inject the backend client and let the application lifecycle close it.

Scheduler, HTTP, operator, and coroutine frameworks should call the coordination boundary; they should not hide lock acquisition inside unrelated business services. That separation keeps contention and takeover visible in tests and metrics.

## Configuration {#configuration}

Review candidate set, scorer inputs, weights that sum to a meaningful policy, tie handling, and stable node identities. Use the same logical lock identity on every replica and different identities for unrelated jobs or environments.

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
./gradlew :examples:strategic-election:test
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

The sample elector is local and does not coordinate separate processes. Use a distributed backend when winner enforcement must cross JVM boundaries.

The demo favors a compact, observable scenario. It does not define production topology, credential rotation, capacity planning, disaster recovery, or a universal exactly-once guarantee.

<!-- release-readme-diagrams:start -->
## Release diagrams {#release-diagrams}

These diagrams are copied byte-for-byte from README assets in the `0.4.0` release tag. They describe this manual's released structure and runtime flows, not later Snapshot changes. Select a preview to open the SVG source.

### Strategic Election Architecture diagram

[![Strategic Election Architecture diagram](../../assets/readme-diagrams/examples-strategic-election-architecture-01.png)](../../assets/readme-diagrams/examples-strategic-election-architecture-01.svg)

_Release README: [`examples/strategic-election/README.md`](https://github.com/bluetape4k/bluetape4k-leader/blob/17ab7f872c1f96318c73d3580729cac20a67e017/examples/strategic-election/README.md)_

### Strategic election flow diagram

[![Strategic election flow diagram](../../assets/readme-diagrams/examples-strategic-election-flow-01.png)](../../assets/readme-diagrams/examples-strategic-election-flow-01.svg)

_Release README: [`examples/strategic-election/README.md`](https://github.com/bluetape4k/bluetape4k-leader/blob/17ab7f872c1f96318c73d3580729cac20a67e017/examples/strategic-election/README.md)_

### Strategic election scenario diagram

[![Strategic election scenario diagram](../../assets/readme-diagrams/examples-strategic-election-scenario-01.png)](../../assets/readme-diagrams/examples-strategic-election-scenario-01.svg)

_Release README: [`examples/strategic-election/README.md`](https://github.com/bluetape4k/bluetape4k-leader/blob/17ab7f872c1f96318c73d3580729cac20a67e017/examples/strategic-election/README.md)_

### Strategic Election Sequence Flow diagram

[![Strategic Election Sequence Flow diagram](../../assets/readme-diagrams/examples-strategic-election-sequence-01.png)](../../assets/readme-diagrams/examples-strategic-election-sequence-01.svg)

_Release README: [`examples/strategic-election/README.md`](https://github.com/bluetape4k/bluetape4k-leader/blob/17ab7f872c1f96318c73d3580729cac20a67e017/examples/strategic-election/README.md)_

<!-- release-readme-diagrams:end -->

## Sources {#sources}

- [Stable example README](../../../../examples/strategic-election/README.md)
- [Gradle build](../../../../examples/strategic-election/build.gradle.kts)
- [Main implementation](../../../../examples/strategic-election/src/main/kotlin/io/bluetape4k/leader/examples/strategy/StrategicElectionDemo.kt)
- [Verification test](../../../../examples/strategic-election/src/test/kotlin/io/bluetape4k/leader/examples/strategy/StrategicElectionDemoTest.kt)
