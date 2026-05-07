# AGENTS.md - bluetape4k-leader

Distributed leader election library with blocking, async, coroutine, and
virtual-thread APIs. Backends include Redis/Lettuce, Redis/Redisson, Exposed,
MongoDB, and Hazelcast.

- Group: `io.github.bluetape4k.leader`
- Publishing: Maven Central through NMCP

## Layout

```text
leader-bom/
leader-core/
leader-redis-lettuce/
leader-redis-redisson/
leader-exposed-core/
leader-exposed-jdbc/
leader-exposed-r2dbc/
leader-mongodb/
leader-hazelcast/
leader-micrometer/
leader-spring-boot/
buildSrc/
```

## Commands

```bash
./gradlew clean build
./gradlew build -x test
./gradlew :leader-core:build
./gradlew :leader-redis-lettuce:test
./gradlew :leader-redis-redisson:test
./gradlew test --tests "io.bluetape4k.leader.redisson.RedissonLeaderElectionTest"
./gradlew :leader-spring-boot:test
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
- `suspend`, `Mono`, `Flux`, and `Flow` return types are unsupported for v1.x
  sync-only annotation AOP.
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
