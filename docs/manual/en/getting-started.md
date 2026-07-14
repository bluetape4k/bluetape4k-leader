---
title: "Getting started"
description: "Validate skip-on-contention semantics locally, then replace only the elector with a distributed backend."
releaseRef: 0.4.0
releaseCommit: 17ab7f872c1f96318c73d3580729cac20a67e017
---

# Getting started

Validate skip-on-contention semantics locally, then replace only the elector with a distributed backend.

## Add one managed dependency

Import the central bluetape4k platform and add the core library. The central platform is the consumer-facing version authority; do not make the Leader BOM a second version choice.

```kotlin
implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
implementation("io.github.bluetape4k.leader:bluetape4k-leader-core")
```

## Run the smallest contract

```kotlin
val elector = LocalLeaderElector()
val result = elector.runIfLeader("daily-report") { generateReport() }
if (result == null) logger.debug { "another contender ran daily-report" }
```

Keep the lock name stable across instances. A blank name is invalid. The default options wait up to 5 seconds, lease for 60 seconds, do not impose a minimum hold time, and do not enable auto-extension.

## Move to production

Replace `LocalLeaderElector` with a backend implementation while retaining the guarded action and its idempotency rules. Set `waitTime` to the amount of queuing you actually tolerate, and make `leaseTime` longer than normal execution plus jitter. Test two contenders, action failure, backend loss, and process restart before rollout.

## Release sources

- [`leader-core/src/main/kotlin/io/bluetape4k/leader/local/LocalLeaderElector.kt`](../../../leader-core/src/main/kotlin/io/bluetape4k/leader/local/LocalLeaderElector.kt)
- [`leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderElectionOptions.kt`](../../../leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderElectionOptions.kt)
- [`leader-core/src/test/kotlin/io/bluetape4k/leader/local/AbstractLocalLeaderElectorTest.kt`](../../../leader-core/src/test/kotlin/io/bluetape4k/leader/local/AbstractLocalLeaderElectorTest.kt)

## Continue learning

- [Bluetape4k Leader manual](index.md)
- [Result semantics](core/result-semantics.md)
- [Choose a backend](guides/backend-selection.md)
