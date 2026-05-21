# Issue 248/229 Examples Design

## Context

Milestone `0.1.1` has two implementation issues selected for this work:

- #248: Add a K3s-backed Kubernetes `coordination.k8s.io/v1 Lease` integration example.
- #229: Add a leader-coordinated rate limiter example using Lettuce Redis and Bucket4j.

Both issues add example modules, so repo-local rules require module registration,
CI paths, scheduled examples workflow entries, README pairs, and targeted tests.

## Goals

1. Add `examples/k8s-lease` as a privileged-runner example that proves Kubernetes
   Lease acquire, conflict, release, and reacquire behavior against `K3sServer`.
2. Add `examples/rate-limiter` as a three-node demo where only the elected
   leader dispatches work and all nodes share one Redis-backed token bucket.
3. Keep production leader modules unchanged unless compilation requires a small
   dependency catalog update.
4. Keep K3s tests out of normal CI test lanes; run them through a dedicated
   `k8sTest` Gradle task and scheduled workflow entry.

## Non-Goals

- Do not implement a publishable `leader-k8s` backend module in this work.
- Do not add Kubernetes operator manifests for #231.
- Do not change Maven Central publishing configuration; examples are already
  excluded by path.
- Do not run privileged K3s tests locally unless the environment supports it.

## Current Evidence

- `K3sServer` exists in `bluetape4k-testcontainers` as
  `io.bluetape4k.testcontainers.infra.K3sServer`.
- `K3sServer.Launcher.k3s` starts the singleton server and `kubernetesClient()`
  returns a fabric8 client.
- `K3sServer` declares fabric8 as compile-only, so the example must add
  `io.fabric8:kubernetes-client` at runtime/test runtime.
- fabric8 7.6.1 includes `Lease`, `LeaseBuilder`, and `LeaseSpecBuilder` under
  `io.fabric8.kubernetes.api.model.coordination.v1`.
- Existing example modules are registered in `settings.gradle.kts`, CI
  `paths-filter`, and `.github/workflows/examples.yml`.

## Design

### #248: `examples/k8s-lease`

Create a small example module with:

- `build.gradle.kts`
  - `application` plugin.
  - Dependencies: `bluetape4k-testcontainers`, `testcontainers`,
    `testcontainers-junit-jupiter`, `fabric8-kubernetes-client`, and
    `bluetape4k-junit5`.
  - A dedicated `k8sTest` task that includes only `@Tag("k8s")`.
  - The normal `test` task excludes `@Tag("k8s")`.
- `K8sLeaseLeaderElectionExample.kt`
  - A lightweight Lease helper that attempts acquisition by creating the Lease
    if absent or replacing it only when the current holder is absent/expired or
    already owned by the requester.
  - Release clears the holder only when the caller still owns the Lease.
  - Demo prints `ACQUIRED`, `CONFLICT`, and `RELEASED` outcomes.
- `K8sLeaseLeaderElectionExampleTest.kt`
  - Uses `K3sServer.Launcher.k3s`.
  - Tagged `@Tag("k8s")`.
  - Verifies acquire, conflict, release, and reacquire.
  - Cleans up the Lease in `try/finally`.
- `README.md` and `README.ko.md`
  - Document privileged Docker requirement, `k8sTest`, and expected behavior.

Lease handling should prefer typed fabric8 model classes rather than raw JSON
because fabric8 already ships `coordination.v1.Lease`.

### #229: `examples/rate-limiter`

Create a small example module with:

- `build.gradle.kts`
  - `application` plugin.
  - Dependencies: `bluetape4k-leader-redis-lettuce`, `bluetape4k-lettuce`,
    `lettuce-core`, `bluetape4k-bucket4j`, `bucket4j_jdk17-core`,
    `bucket4j_jdk17-lettuce`, `bluetape4k-testcontainers`, and test libs.
- `RateLimiterDemo.kt`
  - Runs three node simulations.
  - Uses Redis-backed leader election for the scheduler role.
  - Uses a Redis-backed Bucket4j bucket named `external-api-quota`.
  - Prints per-node `SCHEDULED`, `CONSUMED`, and `REJECTED` outcomes.
- `ApiScheduler.kt`, `ApiWorker.kt`, `ExternalApiMock.kt`
  - Keep the demo deterministic and testable.
- `RateLimiterDemoTest.kt`
  - Uses `RedisServer.Launcher.redis`.
  - Verifies exactly one scheduler and `totalCalls <= expectedMax` for a
    three-second window.
- `README.md` and `README.ko.md`
  - Explain architecture, run command, and expected output.

The implementation should use existing bluetape4k Bucket4j helpers if the API is
available from the resolved artifact. If that API differs from the issue text,
fall back to direct Bucket4j Lettuce proxy-manager APIs while keeping the public
example focused on leader coordination.

## Workflow/CI Integration

Update:

- `settings.gradle.kts`
  - Include `examples:k8s-lease`.
  - Include `examples:rate-limiter`.
- `.github/workflows/ci.yml`
  - Add path filters and test jobs for `examples-rate-limiter`.
  - Do not run `examples-k8s-lease:k8sTest` in normal CI.
- `.github/workflows/examples.yml`
  - Add `examples-rate-limiter:test`.
  - Add `examples-k8s-lease:k8sTest` only in the scheduled/manual examples lane.
- `gradle/libs.versions.toml`
  - Add fabric8 and Bucket4j aliases missing from this repo catalog.

## Risks

- K3s requires privileged Docker and may not run in local developer or standard
  GitHub-hosted runners. Mitigation: isolate with `@Tag("k8s")` and `k8sTest`.
- `K3sServer` may be present only in newer `bluetape4k-testcontainers` artifacts.
  Mitigation: compile against the current catalog/BOM and report blocker if the
  artifact has not been published.
- Bucket4j helper API may differ from issue text. Mitigation: inspect local
  source jar before implementation and use direct Bucket4j APIs if needed.

## Acceptance

- #248 acceptance criteria are covered by `examples:k8s-lease:k8sTest`.
- #229 acceptance criteria are covered by `examples:rate-limiter:test`.
- `./gradlew projects` lists both new modules.
- Targeted compile/tests pass or a precise environment blocker is reported.
- `actionlint` passes for changed workflows.
