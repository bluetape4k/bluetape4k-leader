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

### Group elector — Redis backends (slot-token TTL model)

`leader-redis-lettuce` / `leader-redis-redisson` 의 group elector 는 모두 **slot-token TTL 모델** 로 동작.

| 항목 | Lettuce (`LettuceSlotTokenGroup`) | Redisson (`RPermitExpirableSemaphore`) |
|---|---|---|
| Key | `lg:{lockName}` ZSET | `lg:{lockName}` permit semaphore |
| Slot 식별자 | `Base58 token (8자)` | Redisson 발급 `permitId` |
| 시간 기준 | Lua `redis.call('TIME')` (server-side) | Redisson 내부 |
| 초기화 | 첫 acquire 시 `ZREMRANGEBYSCORE` 만료 정리 | 첫 access 시 `trySetPermits(maxLeaders)` 멱등 호출 |
| `minLeaseTime > 0` | RELEASE 시 `ZADD XX` 로 score 갱신 | `updateLeaseTime` / `updateLeaseTimeAsync` |
| Crash recovery | `leaseTime` 만료 후 다음 acquire 시 자동 회수 | `leaseTime` 만료 후 Redisson 자동 회수 |

**핵심 계약**:

- `minLeaseTime` 은 backend TTL 에 위임 — caller-park 없음. `runIfLeader` 는 `action` 종료 직후 즉시 반환.
- 외부 reaper / cleanup 작업 불필요 — 두 backend 모두 만료 슬롯 자동 회수.
- `lg:{lockName}` key prefix 는 의도적으로 분리 — 구버전 (`LettuceSemaphore`, `RSemaphore`) 키와 충돌 회피, 롤링 배포 호환.
- Lettuce 측 구버전 `LettuceSemaphore` / `LettuceSuspendSemaphore` 는 `@Deprecated` (소스에 잔존, 새 elector 와 미연결).

## AOP Annotation Guide (`leader-spring-boot`)

`@LeaderElection` / `@LeaderGroupElection` — `leader-core` 선언, AspectJ CTW (Freefair post-compile weaving) 적용.

### 핵심 규칙

- CTW → `open` 불필요 (final Kotlin 메서드에도 동작)
- `@EnableAspectJAutoProxy` **사용 금지** — CTW가 컴파일 타임에 weaving 처리
- `private` 메서드는 인터셉트 안 됨 — startup validation에서 warn/fail
- `suspend` / `Mono` 반환 타입 지원 (#90 / #91). `Flux` / `Flow` 미지원 (validator startup fail/WARN)
- `CompletableFuture` / `Future` / `ListenableFuture` / `kotlinx.coroutines.Deferred` 반환 타입 차단 (#79 — lock release 가 future 완료 전 발생 → split-brain 위험)

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
