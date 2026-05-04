# CLAUDE.md

Guidance for Claude Code when working in this repository.

## Project Overview

`bluetape4k-leader` is a standalone Kotlin/JVM distributed leader election library. It provides blocking, async, coroutine, and virtual-thread APIs for leader election and leader-group (semaphore-based multi-leader) election, backed by Redis (Lettuce, Redisson) with more backends planned.

This project is published independently to Maven Central under group `io.github.bluetape4k.leader`.

## Repository Layout

```
leader-bom/              # BOM (bill of materials) for consumers
leader-core/             # Interfaces + local in-process implementations
leader-redis-lettuce/    # Lettuce-based Redis backend
leader-redis-redisson/   # Redisson-based Redis backend
leader-exposed/          # (planned) Exposed/JDBC backend
leader-mongodb/          # MongoDB backend (findOneAndUpdate + TTL index)
leader-hazelcast/        # (planned) Hazelcast backend
leader-micrometer/       # (planned) Micrometer metrics integration
leader-spring-boot3/     # (planned) Spring Boot 3 auto-configuration
leader-spring-boot4/     # (planned) Spring Boot 4 auto-configuration
buildSrc/                # Versions, plugins, dependency catalog (Libs.kt, Versions.kt)
```

## Build Commands

```bash
./gradlew clean build
./gradlew build -x test
./gradlew :leader-core:build
./gradlew :leader-redis-lettuce:test
./gradlew :leader-redis-redisson:test
./gradlew test --tests "io.bluetape4k.leader.redisson.RedissonLeaderElectionTest"
./gradlew detekt
```

## Key Design Contracts

### `runIfLeader()` returns `T?`

**Never throws** on lock acquisition failure. Returns `null` when the caller is not elected leader (lock not acquired within `waitTime`). This matches ShedLock's skip-on-contention behavior.

```kotlin
val result = leaderElection.runIfLeader("job-lock") { doWork() }
// result == doWork() return value on success, null if not elected
```

### Interfaces

| Interface | Execution model |
|---|---|
| `LeaderElection` | Blocking (synchronous) |
| `AsyncLeaderElection` | `CompletableFuture`-based async |
| `VirtualThreadLeaderElection` | Virtual thread per election |
| `SuspendLeaderElection` | Kotlin coroutine suspend |
| `LeaderGroupElection` | Blocking, semaphore-based multi-leader |
| `SuspendLeaderGroupElection` | Coroutine, semaphore-based multi-leader |

### Options

```kotlin
// Single-leader options
LeaderElectionOptions(waitTime = 5.seconds, leaseTime = 60.seconds)

// Multi-leader group options
LeaderGroupElectionOptions(maxLeaders = 3, waitTime = 5.seconds, leaseTime = 60.seconds)
```

`LeaderElectionOptions.Default` and `LeaderGroupElectionOptions.Default` are pre-built constants.

## Kotlin Edit Workflow (MANDATORY)

After every `.kt` edit:

1. Run `ide_diagnostics` — check import errors and `@Deprecated` warnings
2. Import errors → fix with `ide_optimize_imports`
3. `@Deprecated` → apply Quick Fix via `lsp_code_actions` — never leave unresolved
4. Build/compile only after passing the above steps

## Coding Rules

- **Kotlin 2.3+**, JVM 21 toolchain
- **Coroutines-first**: all async work uses coroutines; wrap blocking APIs with `withContext(Dispatchers.IO)`
- **Null safety**: never use `!!`; prefer `?.`, `?:`, `requireNotNull()`
- **Immutability**: prefer `val`; use `data class` for options/state
- **Error handling**: `runCatching {}` for throwable boundaries; never catch `CancellationException` without rethrowing
- **No `@Synchronized`/`synchronized {}`**: use `reentrantLock()` for virtual-thread safety
- **atomicfu**: class-property level only — never method-local variables

## Test Infrastructure

모든 테스트는 **`bluetape4k-testcontainers`의 `XxxServer.Launcher.xxx` 표준** 사용. `GenericContainer` 직접 사용 금지. 컨테이너 자동 시동 + JVM 종료 시 정리 + 모듈 간 일관성 보장.

```kotlin
// Redis
abstract class AbstractRedissonLeaderTest {
    companion object: KLogging() {
        val redis = RedisServer.Launcher.redis
        val redisUrl: String get() = redis.url
    }
}

// MongoDB
abstract class AbstractMongoLeaderTest {
    companion object: KLogging() {
        val mongo = MongoDBServer.Launcher.mongoDB
        val connectionString: String get() = mongo.url
    }
}
```

테스트 의존성: `testImplementation(libs.bluetape4k.testcontainers)`

## Test Requirements

- JUnit 5 + MockK + Kluent
- `@TestInstance(TestInstance.Lifecycle.PER_CLASS)` on all test base classes
- Use `runTest` for suspend tests (auto-advances virtual time)
- Use backtick-quoted descriptive test names
- Use Kluent comparison matchers (`shouldBeGreaterOrEqualTo`) — never `(x >= y).shouldBeTrue()`

## Commit Convention

Korean + conventional prefix:
```
feat: 새 기능 설명
fix: 버그 수정 설명
refactor: 리팩토링 설명
test: 테스트 추가/수정
docs: 문서 수정
chore: 빌드/설정 변경
```

## Git Workflow

- Base branch: `develop`
- Feature work: `git worktree add .worktrees/<branch> -b <branch>`
- One issue = one PR, squash-merged into `develop`
- `main` branch = publish-only (release cuts only)

## Publishing

```bash
./gradlew publishBluetape4kLeaderPublicationToBluetape4kLeaderRepository           # SNAPSHOT
./gradlew publishBluetape4kLeaderPublicationToBluetape4kLeaderRepository -PsnapshotVersion=  # RELEASE
```

Published to Maven Central via NMCP (New Maven Central Publishing).

## After Code Changes

- [ ] Compile + test changed module
- [ ] Update `README.md` and `README.ko.md` for changed module
- [ ] KDoc updated for new/modified public APIs
