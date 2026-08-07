---
manualId: "bluetape4k-leader-spring-boot"
id: "bluetape4k-leader-spring-boot"
title: "Spring Boot integration"
locale: "en"
kind: "library"
gradlePath: ":bluetape4k-leader-spring-boot"
sourceDir: "leader-spring-boot"
releaseRef: "0.5.0"
artifact: io.github.bluetape4k.leader:bluetape4k-leader-spring-boot
---

# Spring Boot integration

> Library module

## Problem {#problem}

Provides Spring Boot auto-configuration, backend factories, compile-time-woven AOP for `@LeaderElection`/`@LeaderGroupElection`, SpEL lock names, failure policy, and lock scope utilities.

## When to use it {#when-to-use}

Use it for Spring-managed jobs that benefit from declarative election. Use direct elector calls when ownership should remain explicit in application code.

## Coordinates {#coordinates}

Artifact: `io.github.bluetape4k.leader:bluetape4k-leader-spring-boot`

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.leader:bluetape4k-leader-spring-boot")
}
```

## Core concepts {#concepts}

The aspect resolves a factory, builds options, acquires, and invokes the method only when elected. Private methods are not intercepted. This project uses AspectJ compile-time weaving, so `@EnableAspectJAutoProxy` is not the activation switch.

## Quick start {#quick-start}

```kotlin
@Service
class Jobs {
    @LeaderElection(name = "daily-settlement", leaseTime = "30m")
    fun settle(): SettlementReport? = settlementService.settle()
}
```

## API by task {#api-by-task}

Use `@LeaderElection` for one owner and `@LeaderGroupElection` for bounded parallelism. Select a factory with `@LeaderElectionBackend`; `LockAssert`/`LockExtender` work only inside an active scope.

## Recommended patterns {#patterns}

Keep annotated methods externally reachable by the woven aspect, use stable or validated SpEL names, disable method invocation in SpEL unless trusted, and keep `FAIL_OPEN_RUN` for idempotent work only.

## Integrations {#integrations}

Auto-configuration supports available backend clients and Micrometer recording. batch-scheduler and webhook-poller show annotation-driven jobs.

## Configuration {#configuration}

Configure defaults under `bluetape4k.leader`, backend-specific properties, AOP order, failure mode, SpEL policy, single/group leases, and factory bean selection.

## Failure modes {#failures}

`RETHROW` surfaces backend failure, `SKIP` suppresses execution, and `FAIL_OPEN_RUN` executes without ownership. Invalid annotations or ambiguous factories should fail startup. Long streams need explicit renewal.

## Operations {#operations}

Monitor elected/skipped/failure outcomes, selected factory, resolved lock name, duration, and extension. Document fail-open decisions in the runbook.

## Testing {#testing}

Use application-context tests for auto-configuration and CTW interception; cover private/non-intercepted methods, SpEL, meta-annotations, failure modes, suspend/reactive results, and cancellation.

## Workshops and learning path {#workshops}

Start with batch-scheduler, then webhook-poller and prometheus-dashboard. Read the chosen backend manual for its client and lease operations.

## Limitations {#limitations}

AOP cannot make arbitrary side effects exactly once. Self/private invocation and long-lived streams require careful boundary testing; `FAIL_OPEN_RUN` permits duplicates by design.

<!-- release-readme-diagrams:start -->
## Release diagrams {#release-diagrams}

These diagrams are loaded directly from README assets published with the `0.5.0` release and pinned to its immutable commit. They describe this manual's released structure and runtime flows, not later Snapshot changes. Select a preview to open the SVG at the same release commit.

### leader spring boot Architecture diagram

[![leader spring boot Architecture diagram](https://raw.githubusercontent.com/bluetape4k/bluetape4k-leader/721a9a3808f67489d2bdb8177734325981c24977/docs/images/readme-diagrams/leader-spring-boot-architecture-01.png)](https://github.com/bluetape4k/bluetape4k-leader/blob/721a9a3808f67489d2bdb8177734325981c24977/docs/images/readme-diagrams/leader-spring-boot-architecture-01.svg)

_Release README: [`leader-spring-boot/README.md`](https://github.com/bluetape4k/bluetape4k-leader/blob/721a9a3808f67489d2bdb8177734325981c24977/leader-spring-boot/README.md)_

### Sequence: AOP-triggered runIfLeader diagram

[![Sequence: AOP-triggered runIfLeader diagram](https://raw.githubusercontent.com/bluetape4k/bluetape4k-leader/721a9a3808f67489d2bdb8177734325981c24977/docs/images/readme-diagrams/leader-spring-boot-sequence-01.png)](https://github.com/bluetape4k/bluetape4k-leader/blob/721a9a3808f67489d2bdb8177734325981c24977/docs/images/readme-diagrams/leader-spring-boot-sequence-01.svg)

_Release README: [`leader-spring-boot/README.md`](https://github.com/bluetape4k/bluetape4k-leader/blob/721a9a3808f67489d2bdb8177734325981c24977/leader-spring-boot/README.md)_

### Sequence: reentrant @LeaderElection diagram

[![Sequence: reentrant @LeaderElection diagram](https://raw.githubusercontent.com/bluetape4k/bluetape4k-leader/721a9a3808f67489d2bdb8177734325981c24977/docs/images/readme-diagrams/leader-spring-boot-sequence-02.png)](https://github.com/bluetape4k/bluetape4k-leader/blob/721a9a3808f67489d2bdb8177734325981c24977/docs/images/readme-diagrams/leader-spring-boot-sequence-02.svg)

_Release README: [`leader-spring-boot/README.md`](https://github.com/bluetape4k/bluetape4k-leader/blob/721a9a3808f67489d2bdb8177734325981c24977/leader-spring-boot/README.md)_

<!-- release-readme-diagrams:end -->

## Sources {#sources}

[Auto-configuration](../../../../leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/LeaderElectionAutoConfiguration.kt) · [Aspect](../../../../leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/aop/LeaderElectionAspect.kt) · [Stable guide](../../../../leader-spring-boot/README.md)

