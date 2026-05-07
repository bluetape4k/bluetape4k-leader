# CLAUDE.md — bluetape4k-leader

분산 리더 선출 라이브러리. blocking/async/coroutine/virtual-thread API, Redis(Lettuce·Redisson) 백엔드.

- **Group**: `io.github.bluetape4k.leader` · **Publishing**: Maven Central (NMCP)

## Repository Layout

```
leader-bom/              # BOM
leader-core/             # Interfaces + local in-process implementations
leader-redis-lettuce/    # Lettuce Redis backend
leader-redis-redisson/   # Redisson Redis backend
leader-exposed-core/     # Exposed ORM common types
leader-exposed-jdbc/     # Exposed JDBC backend
leader-exposed-r2dbc/    # Exposed R2DBC backend
leader-mongodb/          # MongoDB backend (findOneAndUpdate + TTL index)
leader-hazelcast/        # Hazelcast backend
leader-micrometer/       # Micrometer metrics integration
leader-spring-boot/      # Spring Boot 4 auto-configuration + AOP (AspectJ CTW)
buildSrc/                # Versions, plugins, dependency catalog
```

## Build Commands

```bash
./gradlew clean build
./gradlew build -x test
./gradlew :leader-core:build
./gradlew :leader-redis-lettuce:test
./gradlew :leader-redis-redisson:test
./gradlew test --tests "io.bluetape4k.leader.redisson.RedissonLeaderElectionTest"
./gradlew :leader-spring-boot:test
./gradlew detekt
./gradlew publishBluetape4kLeaderPublicationToBluetape4kLeaderRepository           # SNAPSHOT
./gradlew publishBluetape4kLeaderPublicationToBluetape4kLeaderRepository -PsnapshotVersion=  # RELEASE
```

## Key Design Contracts

### `runIfLeader()` 반환값

**절대 throw 하지 않음**. 리더 선출 실패(lock 미획득) 시 `null` 반환. ShedLock 의 skip-on-contention 동작과 동일.

```kotlin
val result = leaderElection.runIfLeader("job-lock") { doWork() }
// result == doWork() 반환값 (선출 성공) 또는 null (미선출)
```

### Interfaces

| Interface | Execution model |
|---|---|
| `LeaderElector` | Blocking (synchronous) |
| `AsyncLeaderElector` | `CompletableFuture`-based async |
| `VirtualThreadLeaderElector` | Virtual thread per election |
| `SuspendLeaderElector` | Kotlin coroutine suspend |
| `LeaderGroupElector` | Blocking, semaphore-based multi-leader |
| `SuspendLeaderGroupElector` | Coroutine, semaphore-based multi-leader |

### Options

```kotlin
LeaderElectionOptions(waitTime = 5.seconds, leaseTime = 60.seconds)
LeaderGroupElectionOptions(maxLeaders = 3, waitTime = 5.seconds, leaseTime = 60.seconds)
// Default constants: LeaderElectionOptions.Default, LeaderGroupElectionOptions.Default
```

## AOP Annotation Guide (`leader-spring-boot`)

`@LeaderElection` / `@LeaderGroupElection` — `leader-core` 선언, AspectJ CTW (Freefair post-compile weaving) 적용.

### 핵심 규칙

- CTW → `open` 불필요 (final Kotlin 메서드에도 동작)
- `@EnableAspectJAutoProxy` **사용 금지** — CTW가 컴파일 타임에 weaving 처리
- `private` 메서드는 인터셉트 안 됨 — startup validation에서 warn/fail
- `suspend` / `Mono` / `Flux` / `Flow` 반환 타입 미지원 (v1.x sync-only)

### SpEL name 규칙

```kotlin
name = "my-lock"                  // ✅ static
name = "'prefix-' + #param"       // ✅ dynamic (리터럴은 SpEL 내부에서 quote)
name = "\${app.lock.name}"        // ✅ Spring placeholder
name = "prefix-#param"            // ❌ prefix- 가 identifier로 파싱됨 → startup failure
```

### Startup validation (`LeaderAnnotationValidatorBeanPostProcessor`)

- `strict = false` (default): footgun → WARN log
- `strict = true`: final/private/suspend 메서드 → `IllegalStateException` throw
- `maxLeaders ≤ 1` + SpEL 파싱 오류 → strict 무관하게 항상 throw

### AutoConfiguration 로드 순서

```
LeaderElectionAutoConfiguration         ← backend election beans
LeaderAopFactoryAutoConfiguration       ← LeaderElectionFactory beans
  ↓ (after)
LeaderMicrometerAutoConfiguration       ← MicrometerLeaderAopMetricsRecorder
  ↓ (after)
LeaderAopAutoConfiguration              ← LeaderElectionAspect + BPP + HealthIndicator
```
