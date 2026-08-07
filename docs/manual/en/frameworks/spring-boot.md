---
title: "Spring Boot integration"
description: "Auto-configure electors and guard invocations with AspectJ compile-time weaving."
releaseRef: 0.5.0
releaseCommit: 721a9a3808f67489d2bdb8177734325981c24977
---

# Spring Boot integration

Auto-configure electors and guard invocations with AspectJ compile-time weaving.

## Interactive visual companions

The detailed [`LeaderElector` walkthrough](/visual-companions/bluetape4k-leader/leader-elector/) connects lock, token, TTL, lease expiry, `autoExtend`, the direct API, and `@LeaderElection`. The [`LeaderGroupElector` delta](/visual-companions/bluetape4k-leader/leader-group-elector/) adds bounded `maxLeaders` slots and the `@LeaderGroupElection` constraints without repeating the single-leader model.

[![LeaderElector lock and lease visual companion](../../assets/visual-companions/leader-elector.en.png)](/visual-companions/bluetape4k-leader/leader-elector/)

[![LeaderGroupElector slot capacity visual companion](../../assets/visual-companions/leader-group-elector.en.png)](/visual-companions/bluetape4k-leader/leader-group-elector/)

## Weaving model

Release 0.5.0 uses Freefair post-compile AspectJ weaving. Do not add `@EnableAspectJAutoProxy`, and Kotlin methods do not need to be `open`. Private methods are not intercepted; startup validation reports invalid declarations. Verify the woven application artifact, not only a plain unit test.

## Annotations

`@LeaderElection` supports nullable synchronous and suspend results plus Mono, Flux, and Flow. Long streams require `autoExtend=true`, or `streamBounded=true` only when completion is guaranteed inside the lease. `@LeaderGroupElection` supports synchronous, suspend, and Mono, but rejects Flux and Flow because per-slot stream extension is undefined.

## Configuration safety

Use valid SpEL such as `"'prefix-' + #param"`. Invalid expressions and impossible group settings fail validation. Auto-configuration orders elector creation, AOP factories, Micrometer, then aspects so instrumentation sees the same execution boundary.

## Release sources

- [`leader-spring-boot/README.md`](../../../../leader-spring-boot/README.md)
- [`leader-core/src/main/kotlin/io/bluetape4k/leader/annotation/LeaderElection.kt`](../../../../leader-core/src/main/kotlin/io/bluetape4k/leader/annotation/LeaderElection.kt)
- [`leader-core/src/main/kotlin/io/bluetape4k/leader/annotation/LeaderGroupElection.kt`](../../../../leader-core/src/main/kotlin/io/bluetape4k/leader/annotation/LeaderGroupElection.kt)

## Continue learning

- [Bluetape4k Leader manual](../index.md)
- [Spring Boot or Ktor](../guides/spring-vs-ktor.md)
- [Micrometer integration](micrometer.md)
