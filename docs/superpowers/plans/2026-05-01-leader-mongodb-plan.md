# leader-mongodb 구현 플랜

- 스펙: docs/superpowers/specs/2026-05-01-leader-mongodb-design.md
- 작성일: 2026-05-01
- 상태: Draft v2 (자체 리뷰 반영)
- 베이스 브랜치: `develop`
- 워크트리: `.worktrees/feat/leader-mongodb`
- 모듈 패키지: `io.bluetape4k.leader.mongodb`

---

## 0. 진입 전 사전 점검

> ✅ = 워크트리 현재 상태에서 이미 완료 확인 (2026-05-01 기준)

- [✅] `settings.gradle.kts` 에 `"leader-mongodb"` 포함 — **이미 완료, 검증만**
- [✅] `buildSrc/.../Libs.kt` 에 `Versions.mongo_driver = "5.6.4"`, `Libs.mongodb_driver_sync`, `Libs.mongodb_driver_kotlin_coroutine`, `Libs.testcontainers_mongodb` 모두 정의됨 — **이미 완료, 검증만**
- [✅] `leader-bom/build.gradle.kts` 에 `api(project(":leader-mongodb"))` 포함 — **이미 완료, 검증만**
- [✅] `leader-mongodb/build.gradle.kts` 작성됨 (`compileOnly(mongodb_driver_kotlin_coroutine)` 확인) — **이미 완료**
- [ ] `leader-hazelcast` 패턴 (`HazelcastLock`, `HazelcastSuspendLock`, `HazelcastLeaderElection`, `HazelcastSuspendLeaderElection`, `HazelcastLeaderGroupElection`, `HazelcastSuspendLeaderGroupElection`) 일독
- [ ] `leader-core` 인터페이스 전체 멤버 확인 (실측):
  - `LeaderGroupElectionState`: `val maxLeaders: Int`, `activeCount()`, `availableSlots()`, `state()`
  - `AsyncLeaderGroupElection extends LeaderGroupElectionState`: `runAsyncIfLeader(lockName, executor, action)`
  - `LeaderGroupElection extends AsyncLeaderGroupElection`: `runIfLeader(lockName, action)` 추가
  - `AsyncLeaderElection`: `runAsyncIfLeader(lockName, executor = VirtualThreadExecutor, action)`

---

## 실행 그룹 (병렬/순차)

### Group 0 — 옵션 + 모듈 부트스트랩 (병렬, 선행 없음)

| 태스크 | 파일 | complexity | depends_on | 비고 |
|---|---|---|---|---|
| T10a | `settings.gradle.kts`, `leader-mongodb/build.gradle.kts` | low | — | ✅ 이미 완료 — 검증만 |
| T10b | `buildSrc/.../Libs.kt` mongodb 디펜던시 | low | — | ✅ 이미 완료 (`Versions.mongo_driver`) — 검증만 |
| T0a | `MongoLeaderElectionOptions.kt` | low | — | |
| T0b | `MongoLeaderGroupElectionOptions.kt` | low | — | |

### Group 1 — 락 핵심 구현 (T0a/T0b/T10 완료 후, T1/T2 병렬)

| 태스크 | 파일 | complexity | depends_on |
|---|---|---|---|
| T1 | `lock/MongoLock.kt` | high | T0a, T10a, T10b |
| T2 | `lock/MongoSuspendLock.kt` | high | T0a, T10a, T10b |

### Group 2 — Election 구현 (T1, T2 완료 후, T3~T6 병렬)

| 태스크 | 파일 | complexity | depends_on |
|---|---|---|---|
| T3 | `MongoLeaderElection.kt` (sync + async + ext) | medium | T1 |
| T4 | `MongoSuspendLeaderElection.kt` (suspend + ext) | medium | T2 |
| T5 | `MongoLeaderGroupElection.kt` (sync group + ext) | medium | T1, T0b |
| T6 | `MongoSuspendLeaderGroupElection.kt` (suspend group + ext) | medium | T2, T0b |

### Group 3 — 테스트 베이스 (T3~T6 완료 후 일부 진행 가능)

| 태스크 | 파일 | complexity | depends_on |
|---|---|---|---|
| T7 | `AbstractMongoLeaderTest.kt` | low | T1, T10a, T10b |

### Group 4 — 테스트 케이스 (T3~T7 완료 후, T8a~T8d 병렬)

| 태스크 | 파일 | complexity | depends_on |
|---|---|---|---|
| T8a | `MongoLeaderElectionTest.kt` | medium | T3, T7 |
| T8b | `MongoSuspendLeaderElectionTest.kt` | medium | T4, T7 |
| T8c | `MongoLeaderGroupElectionTest.kt` | medium | T5, T7 |
| T8d | `MongoSuspendLeaderGroupElectionTest.kt` | medium | T6, T7 |

### Group 5 — 문서 + BOM + CLAUDE (구현/테스트 완료 후)

| 태스크 | 파일 | complexity | depends_on |
|---|---|---|---|
| T9a | `leader-mongodb/README.md` | low | T3~T6 |
| T9b | `leader-mongodb/README.ko.md` | low | T3~T6 |
| T9c | 루트 `README.md` 백엔드 표 + `CHANGELOG.md` | low | T3~T6 |
| T10c | `leader-bom/build.gradle.kts` artifact 추가 | low | T10a | ✅ 이미 완료 — 검증만 |
| T10d | 루트 `CLAUDE.md` `(planned)` 제거 | low | T3~T6 |

### Group 6 — 최종 검증 (전부 완료 후)

| 태스크 | 작업 | complexity | depends_on |
|---|---|---|---|
| T11 | `./gradlew :leader-mongodb:build` 통과, `:leader-mongodb:detekt` 통과, 커버리지 80%+ | medium | T8a~T8d, T9, T10 |
| T12 | bluetape4k-design Step 6-R 6-Tier 코드 리뷰, CRITICAL/HIGH 0 확인 | high | T11 |

---

## 태스크 상세

### T10a: 모듈 빌드 스크립트 부트스트랩 ✅ 이미 완료
- **파일**:
  - `settings.gradle.kts` — `"leader-mongodb"` 포함 확인됨
  - `leader-mongodb/build.gradle.kts` — `compileOnly(mongodb_driver_kotlin_coroutine)` 포함 확인됨
- **complexity**: low
- **depends_on**: —
- **수행**: **검증만** — `./gradlew :leader-mongodb:tasks` 실행하여 정상 응답 확인
- **검증**: `./gradlew :leader-mongodb:tasks` 정상 실행

### T10b: Libs.kt MongoDB 의존성 정의 ✅ 이미 완료
- **파일**: `buildSrc/src/main/kotlin/Libs.kt`
- **complexity**: low
- **depends_on**: —
- **수행**: **검증만** — `Versions.mongo_driver = "5.6.4"`, `Libs.mongodb_driver_sync`, `Libs.mongodb_driver_kotlin_coroutine`, `Libs.testcontainers_mongodb` 모두 존재 확인됨
- **검증**: Gradle sync 성공 확인 (이미 됨)

### T0a: MongoLeaderElectionOptions
- **파일**: `leader-mongodb/src/main/kotlin/io/bluetape4k/leader/mongodb/MongoLeaderElectionOptions.kt`
- **complexity**: low
- **depends_on**: —
- **수행**:
  - 스펙 §3.1 그대로 `data class` 작성 (필드: `leaderOptions`, `retryDelay = 50.ms`, `releaseTimeout = 5.s`)
  - `init { require(retryDelay > Duration.ZERO); require(releaseTimeout > Duration.ZERO) }`
  - `companion object { val Default = MongoLeaderElectionOptions() }`
  - KDoc 한국어 + 사용 예제
- **검증**: `:leader-mongodb:compileKotlin` 통과, `init` 검증 단위 테스트는 T8a 에서 다룸

### T0b: MongoLeaderGroupElectionOptions
- **파일**: `leader-mongodb/src/main/kotlin/io/bluetape4k/leader/mongodb/MongoLeaderGroupElectionOptions.kt`
- **complexity**: low
- **depends_on**: —
- **수행**:
  - 스펙 §3.2 그대로 작성
  - `val maxLeaders: Int get() = leaderGroupOptions.maxLeaders`
  - `init` 검증 (`maxLeaders > 0`, `retryDelay > 0`, `releaseTimeout > 0`)
  - `companion object { val Default = MongoLeaderGroupElectionOptions() }`
  - KDoc 한국어
- **검증**: 컴파일 통과

### T1: MongoLock (sync 토큰 락)
- **파일**: `leader-mongodb/src/main/kotlin/io/bluetape4k/leader/mongodb/lock/MongoLock.kt`
- **complexity**: high
- **depends_on**: T0a, T10a, T10b
- **수행**:
  - `class MongoLock private constructor(private val collection: MongoCollection<Document>, val lockKey: String)`
  - `companion object : KLogging() { const val LOCK_COLLECTION_NAME = "bluetape4k_leader_locks"; const val GROUP_LOCK_COLLECTION_NAME = "bluetape4k_leader_group_locks"; private val ensuredNamespaces = ConcurrentHashMap.newKeySet<String>(); fun ensureIndexes(c) {...}; operator fun invoke(collection, lockKey): MongoLock { ensureIndexes(collection); return MongoLock(collection, lockKey) } }`
  - `private val token = UUID.randomUUID().toString()`
  - `fun tryLock(waitTime, leaseTime): Boolean` — 스펙 §4.4 코드 그대로 (deadline loop, jitter, FindOneAndUpdate upsert + filter `expireAt < now`, returnDocument AFTER, token 비교)
  - 예외 분기 (스펙 §4.4):
    - `MongoWriteException(11000)` → `false`
    - `MongoCommandException` → `when (errorCode) { 11000 -> false; 13, 18 -> { log.error; return false }; else -> warn + false }`
    - `MongoTimeoutException`, `ServerSelectionTimeoutException` → `return false` (즉시)
    - `MongoSecurityException` → `log.error; return false`
    - `MongoException` → warn + `false`
  - `fun unlock()` — `deleteOne(and(eq("_id", lockKey), eq("token", token)))`; `deletedCount == 0L` 시 warn 로그만
  - `fun isHeldByCurrentInstance(): Boolean` — `find(and(eq _id, eq token)).first() != null` (토큰 노출 금지: 메서드 내부만 사용)
  - `Random.nextLong(retryDelay/2 + 1)` jitter, `Thread.sleep(min(retryDelay+jitter, remaining))`
  - 로그 메시지에 `lockKey` 만 포함, **`token` 절대 금지**
  - **테스트 헬퍼** (M2): `internal fun resetEnsuredFor(namespace: String) { ensuredNamespaces.remove(namespace) }` — `ensuredNamespaces` 가 private 이므로 재시도 테스트에서 이 헬퍼를 사용. visibility는 `internal` 로 제한하여 detekt 규칙 위반 방지.
- **검증**:
  - `:leader-mongodb:compileKotlin` 통과
  - 단위 테스트는 T8a 통합 테스트로 대체
  - detekt 규칙 (no `!!`, complex method 한도) 통과

### T2: MongoSuspendLock (coroutine 토큰 락)
- **파일**: `leader-mongodb/src/main/kotlin/io/bluetape4k/leader/mongodb/lock/MongoSuspendLock.kt`
- **complexity**: high
- **depends_on**: T0a, T10a, T10b
- **수행**:
  - `mongodb-driver-kotlin-coroutine` 의 `MongoCollection<Document>` 사용 (suspend `findOneAndUpdate`, `deleteOne`)
  - `class MongoSuspendLock private constructor(private val collection: KMongoColl, val lockKey: String)`
  - `companion object : KLoggingChannel() { fun ensureIndexes(suspend) {...}; suspend operator fun invoke(...) }`
  - `suspend fun tryLock(waitTime, leaseTime): Boolean` — 스펙 §4.4 동일하지만 `delay(...)` + `kotlin.coroutines.coroutineContext.ensureActive()` 사용
  - **취소 안전성** (스펙 §2.3):
    ```
    finally {
        withContext(NonCancellable) {
            withTimeout(releaseTimeout) {
                if (isHeldByCurrentInstance()) unlock()
            }
        }
    }
    ```
    → `tryLock`/`unlock` 자체에서 `withContext(NonCancellable)` 처리는 호출부 (Election) 가 담당, Lock 은 `unlock()` suspend 함수만 제공
  - **KDoc 경고** (M1): `unlock()` KDoc 에 `"이 함수는 반드시 withContext(NonCancellable) 블록 안에서 호출해야 안전하다. 취소된 컨텍스트에서 직접 호출하면 CancellationException 으로 즉시 중단된다."` 명시
  - `suspend fun unlock()` — token 일치 deleteOne, 결과 0건이면 warn
  - `suspend fun isHeldByCurrentInstance(): Boolean`
  - 예외 분기 T1 과 동일 (Mongo 예외 타입 동일)
- **검증**: 컴파일 통과, T8b/T8d 에서 동작 검증

### T3: MongoLeaderElection (sync + async + 확장 함수)
- **파일**: `leader-mongodb/src/main/kotlin/io/bluetape4k/leader/mongodb/MongoLeaderElection.kt`
- **complexity**: medium
- **depends_on**: T1
- **수행**:
  - `class MongoLeaderElection private constructor(private val collection: MongoCollection<Document>, val options: MongoLeaderElectionOptions) : LeaderElection, AsyncLeaderElection`
  - `companion object : KLogging() { operator fun invoke(collection, options = Default): MongoLeaderElection { MongoLock.ensureIndexes(collection); return MongoLeaderElection(collection, options) } }`
  - `override fun <T> runIfLeader(lockName, action): T?`
    - lockName 검증 (`requireNotBlank`, `!contains('.')`, `!contains(":slot:")`) — `IllegalArgumentException` (스펙 §2.1, §4.2)
    - `val lock = MongoLock(collection, lockName)`
    - `if (!lock.tryLock(options.waitTime, options.leaseTime)) return null`
    - `try { action() } finally { if (lock.isHeldByCurrentInstance()) runCatching { lock.unlock() }.onFailure { log.warn(...) } }` — **isHeldByCurrentInstance() 가드 필수** (H4): takeover 발생 시 불필요한 deleteOne + warn noise 방지
  - `override fun <T> runAsyncIfLeader(lockName, action): CompletableFuture<T?>` — 스펙 §2.2 세 경로:
    1. tryLock 실패 → `CompletableFuture.completedFuture(null)`
    2. tryLock 성공 후 `action()` 호출이 동기적으로 throw → `lock.unlock()` 후 `CompletableFuture.failedFuture(e)`
    3. `action()` 이 반환한 CF 완료 시 → `cf.whenCompleteAsync { _, _ -> runCatching { lock.unlock() } }`
  - 확장 함수: `fun MongoCollection<Document>.runIfLeader(lockName, options, action): T?` 등 위임
- **검증**: 컴파일 통과, T8a 에서 검증

### T4: MongoSuspendLeaderElection (suspend + 확장 함수)
- **파일**: `leader-mongodb/src/main/kotlin/io/bluetape4k/leader/mongodb/MongoSuspendLeaderElection.kt`
- **complexity**: medium
- **depends_on**: T2
- **수행**:
  - `class MongoSuspendLeaderElection private constructor(...) : SuspendLeaderElection`
  - `companion object : KLoggingChannel() { suspend operator fun invoke(...) { ensureIndexes; ... } }`
  - `override suspend fun <T> runIfLeader(lockName, action): T?`
    - lockName 검증
    - `val lock = MongoSuspendLock(collection, lockName)`
    - `if (!lock.tryLock(...)) return null`
    - 스펙 §2.3 finally 패턴:
      ```
      try {
          action()
      } finally {
          withContext(NonCancellable) {
              try {
                  withTimeout(releaseTimeout.toMillis()) {
                      if (lock.isHeldByCurrentInstance()) lock.unlock()
                  }
              } catch (e: TimeoutCancellationException) { log.warn { "..." } }
                catch (e: MongoException) { log.warn(e) { "..." } }
          }
      }
      ```
  - 확장 함수: `suspend fun MongoCollection<Document>.suspendRunIfLeader(...)`
- **검증**: 컴파일 통과, T8b 에서 검증

### T5: MongoLeaderGroupElection (sync 그룹 + 확장 함수)
- **파일**: `leader-mongodb/src/main/kotlin/io/bluetape4k/leader/mongodb/MongoLeaderGroupElection.kt`
- **complexity**: medium
- **depends_on**: T1, T0b
- **수행**:
  - `class MongoLeaderGroupElection private constructor(private val groupCollection: MongoCollection<Document>, val options: MongoLeaderGroupElectionOptions) : LeaderGroupElection`
  - `companion object : KLogging() { operator fun invoke(...) { MongoLock.ensureIndexes(groupCollection); ... } }`
  - **`LeaderGroupElectionState` 멤버 필수 구현** (H2 — 누락 시 컴파일 실패):
    - `override val maxLeaders: Int get() = options.maxLeaders`
    - `override fun state(lockName: String): LeaderGroupState = LeaderGroupState(lockName, maxLeaders, activeCount(lockName))`
  - **`AsyncLeaderGroupElection.runAsyncIfLeader` 필수 구현** (H3 — `LeaderGroupElection extends AsyncLeaderGroupElection`):
    - `override fun <T> runAsyncIfLeader(lockName, executor, action): CF<T?>` — T3의 async 3-경로 동일 패턴 적용
      1. tryLock 실패 → `completedFuture(null)`
      2. `action()` 동기 throw → `lock.unlock(); failedFuture(e)`
      3. CF 완료 → `whenCompleteAsync { _, _ -> if (lock.isHeldByCurrentInstance()) unlock() }`
  - `override fun <T> runIfLeader(lockName, action): T?`
    - lockName 검증 (단일과 동일)
    - `val perSlotWait = options.leaderGroupOptions.waitTime / maxLeaders`
    - `val start = Random.nextInt(maxLeaders)` (핫스팟 방지)
    - `for (i in 0 until maxLeaders) { val slot = (start + i) % maxLeaders; val key = "$lockName:slot:$slot"; val lock = MongoLock(groupCollection, key); if (lock.tryLock(perSlotWait, leaseTime)) try { return action() } finally { **if (lock.isHeldByCurrentInstance())** runCatching { lock.unlock() } } }` — **isHeldByCurrentInstance() 가드 필수** (H4)
    - 모든 슬롯 실패 → `null`
  - `override fun activeCount(lockName): Int` — 스펙 §4.6 그대로 (`Filters.regex("_id", "^${Regex.escape(lockName)}:slot:\\d+$")` + `Filters.gt("expireAt", Date())`)
  - `override fun availableSlots(lockName): Int = maxLeaders - activeCount(lockName)`
  - 확장 함수: `fun MongoCollection<Document>.runIfLeaderGroup(...)`
  - KDoc: `activeCount` / `availableSlots` 는 근사치임 명시; `runAsyncIfLeader` 는 T3 패턴 준용
- **검증**: 컴파일 통과, T8c 에서 검증

### T6: MongoSuspendLeaderGroupElection (suspend 그룹 + 확장 함수)
- **파일**: `leader-mongodb/src/main/kotlin/io/bluetape4k/leader/mongodb/MongoSuspendLeaderGroupElection.kt`
- **complexity**: medium
- **depends_on**: T2, T0b
- **수행**:
  - `class MongoSuspendLeaderGroupElection private constructor(...) : SuspendLeaderGroupElection`
  - **`LeaderGroupElectionState` 멤버 필수 구현** (H2 — `SuspendLeaderGroupElection` 의 부모 체계 확인 후 동일 패턴):
    - `override val maxLeaders: Int get() = options.maxLeaders`
    - `override fun state(lockName: String): LeaderGroupState = LeaderGroupState(lockName, maxLeaders, activeCount(lockName))`
    - (SuspendLeaderGroupElection 이 suspend activeCount 를 별도로 요구하면 그 시그니처 따름)
  - `override suspend fun <T> runIfLeaderGroup(lockName, action): T?`
    - 검증 + 슬롯 랜덤 시작 + per-slot waitTime
    - **슬롯 루프 시작마다 `currentCoroutineContext().ensureActive()` 호출** (M3): 취소된 코루틴이 불필요하게 모든 슬롯 시도하는 것 방지
    - 슬롯별 `MongoSuspendLock` 사용
    - `try { action() } finally { withContext(NonCancellable) { withTimeout(releaseTimeout) { if (lock.isHeldByCurrentInstance()) lock.unlock() } } }` — **isHeldByCurrentInstance() 가드 필수** (H4)
  - `override suspend fun activeCount(lockName): Int` — coroutine driver 의 `countDocuments(...).awaitSingle()` / Flow collect
  - `override suspend fun availableSlots(lockName): Int`
  - 확장 함수: `suspend fun MongoCollection<Document>.suspendRunIfLeaderGroup(...)`
- **검증**: 컴파일 통과, T8d 에서 검증

### T7: AbstractMongoLeaderTest
- **파일**: `leader-mongodb/src/test/kotlin/io/bluetape4k/leader/mongodb/AbstractMongoLeaderTest.kt`
- **complexity**: low
- **depends_on**: T10a, T10b
- **수행**:
  - 스펙 §8.1 그대로:
    - `@TestInstance(PER_CLASS)` `abstract class`
    - `companion object : KLogging() { val mongoContainer = MongoDBContainer("mongo:7"); init { start() }; val mongoUri; val mongoClient by lazy { MongoClients.create(mongoUri).also { ShutdownQueue.register { it.close() } } }; val lockCollection by lazy { ... LOCK_COLLECTION_NAME }; val groupLockCollection by lazy { ... GROUP_LOCK_COLLECTION_NAME } }`
  - 테스트 간 격리: `@BeforeEach` 또는 helper 로 `lockCollection.deleteMany(Filters.empty())`, `groupLockCollection.deleteMany(Filters.empty())`
  - coroutine 테스트용 `kClient: com.mongodb.kotlin.client.coroutine.MongoClient` 도 `by lazy` 노출
- **검증**: `:leader-mongodb:compileTestKotlin` 통과, 컨테이너 부팅

### T8a: MongoLeaderElectionTest
- **파일**: `leader-mongodb/src/test/kotlin/io/bluetape4k/leader/mongodb/MongoLeaderElectionTest.kt`
- **complexity**: medium
- **depends_on**: T3, T7
- **수행 (스펙 §8.2 매핑)**:
  - 정상 획득 → action 결과 반환
  - 경합: 병렬 N=10 → `successCount == 1`
  - 경계 검증 3종 (`""`, `"a.b"`, `"a:slot:b"`) → `IllegalArgumentException`
  - Never-throws: `action throw` → 예외 전파 + collection 에서 lock document 삭제 확인
  - Never-throws: collection 에 mock/stub 으로 `MongoException` 유발 → `null`, throw 없음 (또는 closed client)
  - E11000: 두 인스턴스 동시 시도 → 한쪽 null, throw 없음
  - Takeover: leaseTime=200ms, 첫 호출에서 lock 잡고 unlock 없이 종료 → 200ms+ 대기 후 두 번째 호출 성공
  - Unlock 토큰 불일치: TTL 만료/수동 삭제 후 unlock → warn 만, throw 없음
  - Async sync throw: action 이 CF 반환 전에 throw → `failedFuture(e)`, lock 해제 검증
  - Async CF 완료: action 정상 CF → CF 완료 시 unlock 호출 검증 (collection 비어있음)
  - Auth 13/18: mock 또는 권한 없는 user 로 13 발생 → 즉시 null + error 로그
  - ensureIndexes 실패 재시도: 첫 호출 시 createIndex throw 모킹 → `ensuredNamespaces` 에서 제거 확인, 두 번째 호출 성공 (반사적으로 `ensuredNamespaces` 접근하거나 expose helper 추가 — 검증 가능 범위 내에서 작성)
  - `:slot:` 양 경로: `runIfLeader("a:slot:b")`, `runIfLeaderGroup("a:slot:b")` 둘 다 IAE
- **검증**: `./gradlew :leader-mongodb:test --tests "*MongoLeaderElectionTest"` 통과, AAA 구조, Kluent matcher

### T8b: MongoSuspendLeaderElectionTest
- **파일**: `leader-mongodb/src/test/kotlin/io/bluetape4k/leader/mongodb/MongoSuspendLeaderElectionTest.kt`
- **complexity**: medium
- **depends_on**: T4, T7
- **수행 (스펙 §8.2)**:
  - `runTest` 베이스
  - 정상 / 경합 (`async` × N) / 경계 검증 / never-throws (action throw + Mongo 예외)
  - **취소 안전성**: `launch { runIfLeader { delay(long) } }`; `cancel(); join()`; 그 다음 `lockCollection.find(eq("_id", lockName)).firstOrNull()` 가 `null` 인지 검증
  - Takeover (suspend)
  - Unlock 토큰 불일치
- **검증**: `./gradlew :leader-mongodb:test --tests "*MongoSuspendLeaderElectionTest"` 통과

### T8c: MongoLeaderGroupElectionTest
- **파일**: `leader-mongodb/src/test/kotlin/io/bluetape4k/leader/mongodb/MongoLeaderGroupElectionTest.kt`
- **complexity**: medium
- **depends_on**: T5, T7
- **수행**:
  - maxLeaders=3, 동시 4 → `peakConcurrent.get() shouldBeLessOrEqualTo 3` (AtomicInteger + Thread.sleep action)
  - 정상 unlock 직후 `activeCount(lockName) shouldBeEqualTo 0`
  - 경계: `:slot:` 포함 lockName → IAE
  - activeCount 필터: 직접 manually `expireAt = past` document 삽입 후 `activeCount` 결과가 그 document 를 제외하는지 검증
  - `availableSlots == maxLeaders - activeCount` 일관성
- **검증**: `./gradlew :leader-mongodb:test --tests "*MongoLeaderGroupElectionTest"` 통과

### T8d: MongoSuspendLeaderGroupElectionTest
- **파일**: `leader-mongodb/src/test/kotlin/io/bluetape4k/leader/mongodb/MongoSuspendLeaderGroupElectionTest.kt`
- **complexity**: medium
- **depends_on**: T6, T7
- **수행**:
  - `runTest` + `async` 경합
  - 동시 점유 ≤ maxLeaders
  - **SuspendGroup 취소**: action 중 cancel + join → 슬롯 컬렉션에서 해당 lockName 의 slot document 가 모두 삭제되었는지 검증
  - activeCount/availableSlots suspend 동작 검증
- **검증**: `./gradlew :leader-mongodb:test --tests "*MongoSuspendLeaderGroupElectionTest"` 통과

### T9a: leader-mongodb/README.md
- **파일**: `leader-mongodb/README.md`
- **complexity**: low
- **depends_on**: T3~T6
- **수행**:
  - 영문 사용 가이드:
    - 의존성: `api("io.github.bluetape4k.leader:leader-mongodb")` + **⚠️ suspend API 사용 시 caller 가 `org.mongodb:mongodb-driver-kotlin-coroutine` 를 별도 추가해야 한다** (M5 — `compileOnly` scope)
    - MongoCollection 주입 예제 (단일/그룹 컬렉션 분리 안내)
    - **WriteConcern.MAJORITY 필수 경고 (Replica Set)** — ACKNOWLEDGED vs MAJORITY 보장 차이 표 포함
    - `leaseTime > action p99 × 2` 권고, takeover 한계 명시
    - AP 수준 보장 안내, `mongo:7` 미만 미지원 (Regex.escape PCRE)
- **검증**: 마크다운 lint, 코드 예제 컴파일 가능 형태

### T9b: leader-mongodb/README.ko.md
- **파일**: `leader-mongodb/README.ko.md`
- **complexity**: low
- **depends_on**: T3~T6
- **수행**: T9a 의 한국어 번역
- **검증**: 마크다운 lint

### T9c: 루트 README + CHANGELOG
- **파일**: `README.md`, `CHANGELOG.md`
- **complexity**: low
- **depends_on**: T3~T6
- **수행**:
  - 루트 README 백엔드 표에 MongoDB 행 추가
  - `CHANGELOG.md` 에 `feat: leader-mongodb 모듈 추가` 항목 추가

### T10c: leader-bom artifact 추가 ✅ 이미 완료
- **파일**: `leader-bom/build.gradle.kts`
- **complexity**: low
- **depends_on**: T10a
- **수행**: **검증만** — `api(project(":leader-mongodb"))` 이미 존재 확인됨
- **검증**: `./gradlew :leader-bom:build` 통과

### T10d: 루트 CLAUDE.md 정리
- **파일**: `CLAUDE.md`
- **complexity**: low
- **depends_on**: T3~T6
- **수행**: `leader-mongodb/             # (planned) MongoDB backend` → `leader-mongodb/             # MongoDB backend (driver-sync + driver-kotlin-coroutine)` 로 변경

### T11: 빌드/Lint/Coverage 최종 검증
- **complexity**: medium
- **depends_on**: T8a~T8d, T9, T10
- **수행**:
  - `./gradlew :leader-mongodb:build` (테스트 포함) 통과
  - `./gradlew :leader-mongodb:detekt` 통과
  - `./gradlew :leader-mongodb:koverReport` — Kover HTML 리포트 생성, line coverage 80%+ 확인 (L2: buildSrc Plugins.kover 사용 확인)
  - `./gradlew :leader-bom:build` 통과
- **검증**: 모든 명령어 exit 0, koverReport 커버리지 스크린샷 또는 수치 캡처

### T12: 6중 코드 리뷰 (PR 전 필수)
- **complexity**: high
- **depends_on**: T11
- **수행**: bluetape4k-design Step 6-R 6-Tier 코드 리뷰 (factual / senior / security / consistency / performance / API surface)
- **검증**: CRITICAL 0건, HIGH 0건, 그 외는 후속 이슈로 분리

---

## 위험/주의 사항 (스펙 §9 발췌)

| 위험 | 대응 단계 |
|---|---|
| action > leaseTime takeover | T9a/T9b README 경고 + KDoc |
| split-brain (RS) | T9a README + T0a/T0b/T3/T4/T5/T6 KDoc 의 WriteConcern.MAJORITY 안내 |
| ensureIndexes TOCTOU | T1/T2 KDoc 에 idempotent 주석 |
| slot 핫스팟 | T5/T6 의 `Random.nextInt(maxLeaders)` 시작 |
| 토큰 로깅 노출 | T1/T2 모든 로그 메시지에서 `lockKey` 만 사용 (코드리뷰 T12 에서 grep 검증) |
| `mongo:7` 미만 PCRE 비호환 | T9a README + T7 컨테이너 이미지 고정 |

---

## 완료 정의 (DoD 요약, 스펙 §10 매핑)

- [ ] T0a/T0b: 옵션 클래스 + init 검증
- [ ] T1/T2: Lock (sync/suspend) — 컬렉션 분리, takeover, jitter, Auth(13/18), ensureIndexes guard 복원
- [ ] T3/T4: LeaderElection (sync/suspend) — runAsyncIfLeader 양 경로, NonCancellable 해제
- [ ] T5/T6: LeaderGroupElection (sync/suspend) — 랜덤 슬롯, activeCount expireAt>now, NonCancellable 해제
- [ ] T7/T8a-d: 테스트 — 스펙 §8.2 전 케이스, 커버리지 80%+
- [ ] T9a/T9b/T9c: README ko/en + 루트 README + CHANGELOG
- [ ] T10a-d: settings.gradle.kts, build.gradle.kts, Libs.kt, leader-bom, CLAUDE.md `(planned)` 제거
- [ ] T11: build + detekt 통과
- [ ] T12: 6중 리뷰 CRITICAL/HIGH 0
