# leader-mongodb 모듈 스펙

- 작성일: 2026-05-01
- 모듈: `leader-mongodb`
- 워크트리: `.worktrees/feat/leader-mongodb`
- 상태: Draft v5 (Opus 최종 리뷰 반영 — APPROVE)
- 베이스 브랜치: `develop`

### Changelog

| 버전 | 주요 변경 |
|---|---|
| v5 | `runAsyncIfLeader` tryLock=false 경로 §2.2 명시; ensureIndexes TOCTOU §9 위험 표 추가; 생성자 throw 범위 §7 명시; §8.2 테스트 케이스 3건 추가 (Auth 13/18, ensureIndexes 재시도, :slot: 양 경로); Regex.escape PCRE 호환 노트 |
| v4 | 단일/그룹 컬렉션 분리 (keyspace collision 방지); lockName `:slot:` 금지; ensureIndexes 실패 시 guard 복원; MongoCommandException Auth(13/18) 분기 추가; leaseTime 위험 명시; activeCount regex 개선 (`\\d+$`); WriteConcern 보장 차이 강화 |
| v3 | 옵션 분리(단일/그룹); WriteConcern caller 책임; ensureIndexes namespace-based guard; activeCount expireAt>now 필터; Auth 예외 분기; Options init 검증; runAsyncIfLeader 양 경로 명시; MongoDBContainer |
| v2 | retryDelay 파라미터화; takeover 패턴; jitter retry; 슬롯 랜덤 시작 |
| v1 | 초기 설계 |

## 1. 목표 (Goal)

`bluetape4k-leader` 의 MongoDB 백엔드 구현체를 추가한다. 기존 Hazelcast / Redis 백엔드와
완전히 동일한 인터페이스(`LeaderElection`, `AsyncLeaderElection`, `SuspendLeaderElection`,
`LeaderGroupElection`, `SuspendLeaderGroupElection`)를 만족시키며, MongoDB 의 원자적 upsert 와 TTL index 를 활용한
"Token + ExpireAt" 분산 락 모델을 제공한다.

### 비목표 (Non-goals)

- `VirtualThreadLeaderElection` 구현 — Hazelcast 백엔드와 동일하게 이번 릴리즈 제외
- MongoDB Change Stream 기반 leader watch / push notification (별도 모듈)
- 자동 lease renewal — **이 구현은 lease 자동 갱신이 없다. `action()` 실행 시간이 `leaseTime` 을 초과하면
  다른 노드/스레드가 lock 을 takeover 할 수 있으며, 동일 작업이 동시에 실행될 수 있다.
  장기 실행 작업에는 이 구현이 부적합하다. `leaseTime` 은 `action()` 최대 실행 시간보다 충분히 크게
  설정해야 한다. (권장: p99 실행시간 × 2 이상).**
- Spring Data MongoDB / Reactive 의존 (드라이버만 사용)
- 네트워크 파티션 시 exactly-once 보장 — 이 라이브러리는 **AP 수준** 보장만 제공한다.
  CP 수준이 필요하면 `leader-zookeeper` 를 사용하라.

---

## 2. 인터페이스 계약 (Interface Contracts)

### 2.1 `runIfLeader()` never-throws 계약

| 결과 상황 | 반환값 / 동작 |
|---|---|
| 락 획득 성공 → action 정상 종료 | `action()` 의 반환값 |
| 락 획득 실패 (`waitTime` 초과) | `null` |
| `MongoWriteException` code=11000 (DuplicateKey) | 재시도 후 `null` |
| `MongoCommandException` code=11000 | 재시도 후 `null` |
| `MongoCommandException` code=13/18 (AuthZ/AuthN) | **즉시 `null`** (재시도 없음, **error** 로그) |
| `MongoCommandException` 기타 | `null` (warn 로그, 재시도 루프 계속) |
| `MongoTimeoutException` / `ServerSelectionTimeoutException` | **즉시 `null`** (재시도 없음, warn 로그) |
| `MongoSecurityException` | **즉시 `null`** (재시도 없음, **error** 로그) |
| `MongoException` 기타 | `null` (warn 로그, 재시도 루프 계속) |
| `action()` 내부에서 throw | 예외 전파, finally 에서 락 해제 |
| `lockName.isBlank()` | `IllegalArgumentException` |
| `lockName.contains('.')` | `IllegalArgumentException` |
| `lockName.contains(":slot:")` | `IllegalArgumentException` |

### 2.2 `runAsyncIfLeader()` 계약

Hazelcast 와 동일하게 두 경로 모두 lock release 를 보장한다.

```
1. tryLock=false (리더 아님) → CompletableFuture.completedFuture(null) 반환, unlock 불필요
2. tryLock=true, action() 호출 전 throw (synchronous) → CompletableFuture.failedFuture(e) 반환, lock 해제
3. tryLock=true, action() 이 반환한 CF 완료 시 → whenCompleteAsync { _, _ -> unlock() }
```

### 2.3 코루틴 취소 안전성 계약

```kotlin
finally {
    withContext(NonCancellable) {
        withTimeout(options.releaseTimeout.toMillis()) {
            if (lock.isHeldByCurrentInstance()) lock.unlock()
        }
    }
}
```

- `withTimeout` 초과 시 `TimeoutCancellationException` → swallow + warn 로그
- release 중 `MongoException` → swallow + warn 로그 (외부 전파 없음)
- 토큰 불일치 → `deleteOne` 0건 처리 + warn 로그만

### 2.4 `LeaderGroupElection` 계약

- 슬롯: `"$lockName:slot:$slot"` (slot = 0..maxLeaders-1)
- **슬롯 시작 인덱스 랜덤화**: `Random.nextInt(maxLeaders)` → slot 0 핫스팟 방지
- 슬롯 단위 `waitTime` = `options.leaderGroupOptions.waitTime / maxLeaders`
- `activeCount(lockName)`: 현재 `expireAt > now` 인 slot document 수
  (TTL sweeper 전 만료 document 는 카운트에서 제외)
- `availableSlots(lockName)` = `maxLeaders - activeCount(lockName)` (같은 쿼리 기반)
- ⚠️ 이 값은 근사치다 — 쿼리와 다음 write 사이 race 구간이 있어 SLA 지표로 쓰면 안 된다.

---

## 3. 옵션

### 3.1 MongoLeaderElectionOptions (단일 리더)

```kotlin
data class MongoLeaderElectionOptions(
    val leaderOptions: LeaderElectionOptions = LeaderElectionOptions.Default,
    val retryDelay: Duration = 50.milliseconds,
    val releaseTimeout: Duration = 5.seconds,
) {
    init {
        require(retryDelay > Duration.ZERO) { "retryDelay must be positive: $retryDelay" }
        require(releaseTimeout > Duration.ZERO) { "releaseTimeout must be positive: $releaseTimeout" }
    }

    companion object {
        val Default = MongoLeaderElectionOptions()
    }
}
```

> `WriteConcern` 은 옵션 필드가 아니다. `MongoCollection` 을 주입받을 때 caller 가 이미 설정한
> `withWriteConcern(...)` 이 그대로 사용된다. 팩토리/생성자에서 collection 을 래핑하지 않는다.
> Replica Set 환경에서는 **caller 가 `collection.withWriteConcern(WriteConcern.MAJORITY)` 로 주입**해야
> split-brain 을 방지할 수 있다. 이 사항은 KDoc 과 README 에 필수 안내한다.

### 3.2 MongoLeaderGroupElectionOptions (복수 리더)

```kotlin
data class MongoLeaderGroupElectionOptions(
    val leaderGroupOptions: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
    val retryDelay: Duration = 50.milliseconds,
    val releaseTimeout: Duration = 5.seconds,
) {
    init {
        require(leaderGroupOptions.maxLeaders > 0) { "maxLeaders must be positive" }
        require(retryDelay > Duration.ZERO) { "retryDelay must be positive: $retryDelay" }
        require(releaseTimeout > Duration.ZERO) { "releaseTimeout must be positive: $releaseTimeout" }
    }

    val maxLeaders: Int get() = leaderGroupOptions.maxLeaders

    companion object {
        val Default = MongoLeaderGroupElectionOptions()
    }
}
```

---

## 4. MongoDB 락 전략

### 4.1 Document 구조 / 컬렉션

단일 리더와 그룹 리더는 **별도 컬렉션**을 사용한다. 같은 컬렉션에 `_id=<lockName>` 과
`_id="$lockName:slot:$n"` 을 혼재하면 `runIfLeader("a:slot:0")` 와 `runIfLeaderGroup("a")` 가
동일 문서를 두고 충돌하는 keyspace collision 이 발생한다.

```
컬렉션 1: "bluetape4k_leader_locks"       (상수 LOCK_COLLECTION_NAME)
          → 단일 리더 전용
{
  _id      : <lockName>          // 자연 unique key
  token    : <UUID>              // 소유자 식별자
  expireAt : <ISODate>           // TTL index 대상
}

컬렉션 2: "bluetape4k_leader_group_locks" (상수 GROUP_LOCK_COLLECTION_NAME)
          → 그룹 리더(슬롯 기반) 전용
{
  _id      : "<lockName>:slot:<n>"   // n = 0..maxLeaders-1
  token    : <UUID>
  expireAt : <ISODate>               // TTL index 대상 + activeCount 필터 기준
}
```

> 컬렉션명 underscore — Hazelcast IMap colon(`bluetape4k:leader:locks`)과 의도적으로 다름.
> MongoDB 컬렉션명에서 colon 은 일부 도구와 호환 문제가 있다.

> ⚠️ 로그에 `token` 값 기록 금지. `lockName` 만 로깅.

### 4.2 lockName 검증

```kotlin
lockName.requireNotBlank("lockName")
require(!lockName.contains('.')) {
    "lockName must not contain '.': $lockName — convention 단순화 및 운영/쿼리 가독성"
}
require(!lockName.contains(":slot:")) {
    "lockName must not contain ':slot:': $lockName — 슬롯 key delimiter와 keyspace collision 방지"
}
```

> `.` 금지: 운영·관측·쿼리 convention 단순화 목적.
>
> `:slot:` 금지: 그룹 슬롯 key 는 `"$lockName:slot:$n"` 형태이므로, lockName 자체에 `:slot:` 이 포함되면
> 동일 컬렉션 내에서도 다른 lockName 의 슬롯 key 와 collision 이 발생한다. 이는 **운영 convention 이 아닌
> 실제 keyspace collision** 이므로 단순 금지로 완화한다.
> (Base64URL encoding 은 운영·디버깅 가독성을 심하게 해치므로 채택하지 않는다.)

### 4.3 인덱스 (ensureIndexes)

JVM 전역 flag 대신 **collection namespace 별** guard 를 사용한다.

```kotlin
companion object {
    private val ensuredNamespaces = ConcurrentHashMap.newKeySet<String>()

    fun ensureIndexes(collection: MongoCollection<Document>) {
        val ns = collection.namespace.fullName   // "db.collectionName"
        if (ensuredNamespaces.add(ns)) {
            // add() 가 true 를 반환한 스레드만 createIndex 실행
            try {
                collection.createIndex(
                    Indexes.ascending("expireAt"),
                    IndexOptions().expireAfterSeconds(0)
                )
            } catch (e: Exception) {
                ensuredNamespaces.remove(ns)   // guard 복원 → 다음 호출에서 재시도 가능
                throw e
            }
        }
    }
}
```

- `createIndex` 실패 시 `ensuredNamespaces` 에서 제거 → 다음 인스턴스 생성 시 재시도 가능.
- 네트워크 일시 오류라면 재시도에서 성공; TTL `expireAfterSeconds` 충돌이면 매번 명확히 실패.
- TTL index 없이 조용히 운영되는 것이 더 위험하므로 예외는 항상 전파 (swallow 금지).
- 다른 database/collection 인스턴스가 주입되어도 각각 독립적으로 인덱스 생성.

### 4.4 원자적 획득 (`tryLock`)

```kotlin
fun tryLock(waitTime: Duration, leaseTime: Duration): Boolean {
    val deadline = System.currentTimeMillis() + waitTime.toMillis()

    do {
        val now = Date()
        val newExpireAt = Date(now.time + leaseTime.toMillis())

        // 신규 OR 만료된 lock 원자적 회수
        val filter = Filters.and(
            Filters.eq("_id", lockKey),
            Filters.lt("expireAt", now)
        )
        val update = Updates.combine(
            Updates.set("token", token),
            Updates.set("expireAt", newExpireAt),
        )
        val opts = FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER)

        val acquired = try {
            val updated = collection.findOneAndUpdate(filter, update, opts)
            updated?.getString("token") == token
        } catch (e: MongoWriteException) {
            if (e.error.code == 11000) false   // 다른 토큰 점유 중
            else { log.warn(e) { "MongoWriteException during tryLock: lockKey=$lockKey" }; false }
        } catch (e: MongoCommandException) {
            when (e.errorCode) {
                11000 -> false   // DuplicateKey — 재시도
                13, 18 -> {      // AuthorizationFailed / AuthenticationFailed
                    log.error(e) { "MongoDB AuthZ/AuthN failure (no retry): lockKey=$lockKey" }
                    return false
                }
                else -> { log.warn(e) { "MongoCommandException during tryLock: lockKey=$lockKey" }; false }
            }
        } catch (e: MongoTimeoutException) {
            log.warn(e) { "MongoDB timeout (no retry): lockKey=$lockKey" }
            return false   // 즉시 실패
        } catch (e: ServerSelectionTimeoutException) {
            log.warn(e) { "MongoDB server selection timeout (no retry): lockKey=$lockKey" }
            return false
        } catch (e: MongoSecurityException) {
            log.error(e) { "MongoDB AuthZ/AuthN failure (no retry): lockKey=$lockKey" }
            return false
        } catch (e: MongoException) {
            log.warn(e) { "MongoException during tryLock: lockKey=$lockKey" }
            false
        }

        if (acquired) return true

        val remaining = deadline - System.currentTimeMillis()
        if (remaining > 0) {
            val jitter = Random.nextLong(retryDelay.toMillis() / 2 + 1)
            Thread.sleep(minOf(retryDelay.toMillis() + jitter, remaining))
        }
    } while (System.currentTimeMillis() < deadline)

    return false
}
```

### 4.5 소유자 해제 (`unlock`)

```kotlin
val result = collection.deleteOne(
    Filters.and(
        Filters.eq("_id", lockKey),
        Filters.eq("token", token),
    )
)
if (result.deletedCount == 0L) {
    log.warn { "Lock release skipped — token mismatch (lease expired?). lockKey=$lockKey" }
}
```

### 4.6 `activeCount` 쿼리

그룹 컬렉션(`GROUP_LOCK_COLLECTION_NAME`)에서만 실행한다.
regex 는 `$` 앵커 + `\\d+` 로 정확한 slot 번호만 매칭한다 (부분 문자열 오염 방지).

```kotlin
override fun activeCount(lockName: String): Int =
    groupCollection.countDocuments(
        Filters.and(
            Filters.regex("_id", "^${Regex.escape(lockName)}:slot:\\d+$"),
            Filters.gt("expireAt", Date())   // TTL sweeper 전 만료 document 제외
        )
    ).toInt()
```

> `^...:slot:\\d+$` — `lockName:slot:0:extra` 나 `lockName:slot:` 같은 손상/수동 삽입 문서를 제외.
> TTL sweeper 전 만료 document 는 `expireAt < now` 이므로 자연 제외.
> 쿼리 이후 write race 는 불가피하므로 근사치임을 KDoc 에 명시.
> ⚠️ `Regex.escape` 는 Kotlin/JVM 기준 `\Q...\E` (Pattern.quote) 형식을 출력한다.
> MongoDB 4.0+ (mongo:7 타겟) 의 PCRE 엔진은 이를 지원하지만, 3.x 잔존 환경에서는 regex 매칭이
> 실패할 수 있다. `mongo:7` 미만 버전은 지원 대상 외다.

---

## 5. 모듈 구조 (Module Layout)

```
leader-mongodb/
├── build.gradle.kts
└── src/
    ├── main/kotlin/io/bluetape4k/leader/mongodb/
    │   ├── MongoLeaderElectionOptions.kt          # 단일 리더 옵션
    │   ├── MongoLeaderGroupElectionOptions.kt     # 그룹 리더 옵션
    │   ├── lock/
    │   │   ├── MongoLock.kt                       # sync 토큰 락 (단일: LOCK_COLLECTION_NAME)
    │   │   └── MongoSuspendLock.kt                # coroutine 토큰 락 (단일: LOCK_COLLECTION_NAME)
    │   ├── MongoLeaderElection.kt                 # LeaderElection + AsyncLeaderElection + 확장 함수
    │   ├── MongoSuspendLeaderElection.kt          # SuspendLeaderElection + 확장 함수
    │   ├── MongoLeaderGroupElection.kt            # LeaderGroupElection + 확장 함수 (GROUP_LOCK_COLLECTION_NAME)
    │   └── MongoSuspendLeaderGroupElection.kt     # SuspendLeaderGroupElection + 확장 함수 (GROUP_LOCK_COLLECTION_NAME)
    └── test/kotlin/io/bluetape4k/leader/mongodb/
        ├── AbstractMongoLeaderTest.kt             # 두 컬렉션 모두 초기화
        ├── MongoLeaderElectionTest.kt
        ├── MongoSuspendLeaderElectionTest.kt
        ├── MongoLeaderGroupElectionTest.kt
        └── MongoSuspendLeaderGroupElectionTest.kt
```

### 5.1 MongoClient 소유권 정책

- `MongoLeaderElection` 생성자는 `MongoCollection<Document>` 를 주입받는다.
- 라이브러리는 `MongoClient`/`MongoDatabase` 를 생성하지 않으며 `close()` 를 호출하지 않는다.
- **MongoClient 라이프사이클은 caller 책임.**

#### WriteConcern 보장 수준 (중요)

| 환경 | 권장 WriteConcern | 보장 수준 |
|---|---|---|
| 단일 mongod (개발/테스트) | `ACKNOWLEDGED` (기본값) | 단일 노드 내구성 |
| Replica Set (운영) | **`MAJORITY`** | 과반 노드 내구성, split-brain 완화 |

> ⚠️ Replica Set 환경에서 `ACKNOWLEDGED` (기본값) 를 사용하면 primary failover 시 아직 secondary 에
> 복제되지 않은 write 가 롤백될 수 있다. 이 경우 두 노드가 동시에 lock 을 획득했다고 판단하는
> **split-brain** 이 발생할 수 있다.
>
> **`MAJORITY` 는 이 라이브러리가 제공할 수 있는 최선의 split-brain 완화책이다.**
> 이 사항은 KDoc, README 에 필수 경고로 포함해야 한다.

```kotlin
// Replica Set 환경 필수 패턴
val lockCollection = client.getDatabase("mydb")
    .getCollection("bluetape4k_leader_locks")
    .withWriteConcern(WriteConcern.MAJORITY)   // caller 책임 — MUST for RS

val groupLockCollection = client.getDatabase("mydb")
    .getCollection("bluetape4k_leader_group_locks")
    .withWriteConcern(WriteConcern.MAJORITY)

val election = MongoLeaderElection(lockCollection)
val groupElection = MongoLeaderGroupElection(groupLockCollection)
```

---

## 6. 구현 목록 및 복잡도

| # | 파일 | 역할 | 복잡도 |
|---|---|---|---|
| 0a | `MongoLeaderElectionOptions.kt` | 단일 리더 옵션 | low |
| 0b | `MongoLeaderGroupElectionOptions.kt` | 그룹 리더 옵션 (maxLeaders 포함) | low |
| 1 | `MongoLock.kt` | sync 토큰 락 — takeover, jitter retry, 예외 분기 | high |
| 2 | `MongoSuspendLock.kt` | coroutine 토큰 락 — NonCancellable + withTimeout | high |
| 3 | `MongoLeaderElection.kt` | sync + async + 확장 함수 | medium |
| 4 | `MongoSuspendLeaderElection.kt` | suspend + 확장 함수 | medium |
| 5 | `MongoLeaderGroupElection.kt` | 랜덤 슬롯, activeCount(expireAt>now) + 확장 함수 | medium |
| 6 | `MongoSuspendLeaderGroupElection.kt` | suspend 슬롯 + NonCancellable + 확장 함수 | medium |
| 7 | `AbstractMongoLeaderTest.kt` | MongoDBContainer 베이스 | low |

---

## 7. 패턴 준수 사항

- **생성자/팩토리 throw**: `ensureIndexes` 실패 시 `MongoException` 을 그대로 throw 한다.
  `runIfLeader()` 의 never-throws 계약은 **lock 획득/해제 단계**에만 적용되며 생성자는 해당 없다.
- **private constructor + companion object `invoke()`**
- **로깅**: 블로킹 → `KLogging`, suspend → `KLoggingChannel` (`lock/` 레이어 포함)
- **검증**: `requireNotBlank` + `.` 포함 금지 + `:slot:` 포함 금지
- **slot 랜덤 시작**: `Random.nextInt(maxLeaders)`
- **NonCancellable finally**: `withContext(NonCancellable) { withTimeout(releaseTimeout) { ... } }`
- **immutability**: `private val token = Base58.randomString(8)`
- **`!!` 사용 금지**
- **예외 분기**: `MongoWriteException(11000) → false`, `MongoCommandException(11000) → false`, `MongoCommandException(13/18) → 즉시 false + error`, `MongoTimeoutException → 즉시 false`, `MongoSecurityException → 즉시 false + error`, `MongoException → false + warn`
- **`LOCK_COLLECTION_NAME = "bluetape4k_leader_locks"`** (단일 리더 컬렉션, companion object)
- **`GROUP_LOCK_COLLECTION_NAME = "bluetape4k_leader_group_locks"`** (그룹 리더 컬렉션, companion object)
- **retryDelay jitter**: `retryDelay + Random(0..retryDelay/2)`
- **로그에 token 값 기록 금지**
- **`runAsyncIfLeader`**: synchronous throw → failedFuture + unlock; CF 완료 → whenCompleteAsync unlock
- **확장 함수**: `MongoCollection<Document>.runIfLeader()`, `suspendRunIfLeader()`, `runIfLeaderGroup()`, `suspendRunIfLeaderGroup()`
- **KDoc**: 모든 public class/function — 한국어 + `@param` + `@return` + 사용 예제 + WriteConcern 안내 (Replica Set 사용 시)

---

## 8. 테스트 전략

### 8.1 Testcontainer 베이스

`testcontainers_mongodb` 의 `MongoDBContainer` 를 사용한다 (`GenericContainer` 대신).

```kotlin
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractMongoLeaderTest {
    companion object : KLogging() {
        private val mongoContainer = MongoDBContainer("mongo:7")
        init { mongoContainer.start() }

        val mongoUri: String get() = mongoContainer.connectionString

        val mongoClient: MongoClient by lazy {
            // 테스트: 단일 mongod → WriteConcern.ACKNOWLEDGED (운영 RS → MAJORITY)
            MongoClients.create(mongoUri).also {
                ShutdownQueue.register { it.close() }
            }
        }

        // 단일 리더 컬렉션
        val lockCollection: MongoCollection<Document> by lazy {
            mongoClient.getDatabase("test").getCollection(LOCK_COLLECTION_NAME)
        }

        // 그룹 리더 컬렉션 — 단일 리더와 컬렉션 분리 (keyspace collision 방지)
        val groupLockCollection: MongoCollection<Document> by lazy {
            mongoClient.getDatabase("test").getCollection(GROUP_LOCK_COLLECTION_NAME)
        }
    }
}
```

> `MongoDBContainer` 는 MongoDB 전용 wait strategy 와 `connectionString` URI 를 제공한다.

### 8.2 필수 테스트 케이스

| 카테고리 | 케이스 |
|---|---|
| 정상 획득 | 단일 호출 → action 결과 반환 |
| 경합 | 병렬 N 개, 동시 점유 ≤ 1 (Group: ≤ maxLeaders) |
| 경계 검증 | lockName 공백 → `IllegalArgumentException` |
| 경계 검증 | lockName `.` 포함 → `IllegalArgumentException` |
| 경계 검증 | lockName `:slot:` 포함 → `IllegalArgumentException` |
| Never-throws | action throw → 예외 전파 + 락 해제 확인 |
| Never-throws | `MongoException` 락 단계 → null, 예외 미전파 |
| E11000 | DuplicateKey → null + throw 없음 |
| Takeover | leaseTime=200ms, release 없이 종료 → 200ms 후 재획득 성공 |
| 취소 안전성 | cancel + join → `collection.find(eq("_id", lockName)).firstOrNull() == null` |
| Unlock 불일치 | deletedCount=0 → warn 로그만, throw 없음 |
| LeaderGroup | maxLeaders=3, 동시 4개 → `peakConcurrent.get() shouldBeLessOrEqualTo 3` |
| LeaderGroup | 정상 unlock 직후 `activeCount == 0` (TTL sweeper 대기 없음) |
| Async — sync throw | action() throw before CF → failedFuture + lock 해제 |
| Async — CF 완료 | CF 완료 → whenCompleteAsync 로 unlock |
| SuspendGroup 취소 | group action 중 취소 → 슬롯 release DB 검증 |
| activeCount 필터 | 만료 document 는 activeCount 에서 제외됨을 검증 |
| Auth 오류 (code 13/18) | MongoCommandException code=13 → 즉시 null + error 로그, throw 없음 |
| ensureIndexes 실패 재시도 | createIndex 실패 → ensuredNamespaces 제거 확인, 다음 인스턴스 생성 시 재시도 성공 |
| `:slot:` 검증 (양 경로) | `runIfLeader("a:slot:b")` → `IllegalArgumentException`; `runIfLeaderGroup("a:slot:b")` → 동일 |

### 8.3 테스트 규칙

- `@TestInstance(PER_CLASS)` 모든 베이스
- suspend 테스트 → `runTest`
- 백틱 테스트명, bluetape4k-assertions matcher
- `activeCount` 검증 → 정상 unlock 직후만 (TTL sweeper 대기 금지)
- 취소 안전성 → cancel + join 후 컬렉션 직접 조회

---

## 9. 위험 요소

| 위험 | 완화책 |
|---|---|
| **action 실행 > leaseTime (takeover)** | **비목표 명시 — leaseTime 을 action p99 × 2 이상으로 설정 (KDoc + README 경고)** |
| TTL sweeper 60s 지연 | takeover 패턴 + activeCount expireAt>now 필터 |
| Thundering herd | retryDelay jitter, retryDelay 파라미터화 |
| split-brain (Replica Set) | caller 가 `WithWriteConcern(MAJORITY)` 적용 (KDoc + README **필수 경고**) |
| RS round-trip > retryDelay | retryDelay 를 round-trip p99+여유로 설정 (기본 50ms = 동일DC 기준) |
| NonCancellable release 무한 대기 | `withTimeout(releaseTimeout)` 최대 5s |
| slot 0 핫스팟 | 랜덤 시작 슬롯 |
| lockName `.` / `:slot:` 혼란 및 keyspace collision | `require(!contains('.'))` + `require(!contains(":slot:"))` |
| 단일/그룹 keyspace collision | 컬렉션 분리 (`bluetape4k_leader_locks` / `bluetape4k_leader_group_locks`) |
| DuplicateKeyException 타입 불일치 | `MongoWriteException` 우선 catch, `MongoCommandException` 내 code 분기 |
| MongoCommandException Auth(13/18) retry | `when (errorCode)` 에서 13/18 먼저 분기 → 즉시 false + error |
| ensureIndexes 일시 오류 후 silent skip | 실패 시 `remove(ns)` guard 복원 → 재시도 가능 |
| ensureIndexes TTL 충돌 | 예외 전파 (swallow 금지) |
| activeCount 손상 문서 오염 | regex `^...:slot:\\d+$` 앵커로 정확히 매칭 |
| ensureIndexes TOCTOU 윈도우 | 첫 caller createIndex 수행 중 둘째 caller 가 set에서 true 반환 후 인덱스 완성 전 진입 가능; createIndex 는 idempotent + 빠름이므로 실용적으로 무해; KDoc에 주석 명시 |
| MongoClient 누수 | caller 소유 명시 |
| AP 수준 (파티션 시 복수 리더 가능) | CP 필요 시 leader-zookeeper 사용 권고 |
| 시계 왜곡 | NTP 동기화 필수, 편차 < leaseTime/10 KDoc 경고 |

---

## 10. 완료 정의 (DoD)

### 10.1 구현체

- [ ] `MongoLeaderElectionOptions` (retryDelay, releaseTimeout init 검증)
- [ ] `MongoLeaderGroupElectionOptions` (maxLeaders 포함, init 검증)
- [ ] `MongoLock` — 컬렉션 분리, takeover, jitter, Auth(13/18) 분기, ensureIndexes 실패 guard 복원
- [ ] `MongoSuspendLock` — coroutine driver, 컬렉션 분리, NonCancellable + withTimeout
- [ ] `MongoLeaderElection` — runIfLeader, runAsyncIfLeader (sync throw + whenComplete), 확장 함수
- [ ] `MongoSuspendLeaderElection` — suspend + 확장 함수
- [ ] `MongoLeaderGroupElection` — 랜덤 슬롯, activeCount(expireAt>now), 확장 함수
- [ ] `MongoSuspendLeaderGroupElection` — suspend + NonCancellable + 확장 함수
- [ ] `LOCK_COLLECTION_NAME` + `GROUP_LOCK_COLLECTION_NAME` 상수, `invoke()` 팩토리
- [ ] KLogging/KLoggingChannel 구분 (lock/ 포함)
- [ ] lockName 검증 (blank + `.` + `:slot:`)
- [ ] 로그에 token 기록 금지
- [ ] KDoc 한국어 + WriteConcern 안내
- [ ] `settings.gradle.kts` include 확인
- [ ] `leader-bom` artifact 추가
- [ ] `./gradlew :leader-mongodb:build -x test` 통과
- [ ] `./gradlew :leader-mongodb:detekt` 통과

### 10.2 테스트

- [ ] `AbstractMongoLeaderTest` — `MongoDBContainer("mongo:7")`, PER_CLASS, ShutdownQueue
- [ ] 4개 테스트 클래스 — 섹션 8.2 전 케이스
- [ ] 커버리지 80%+

### 10.3 문서

- [ ] `leader-mongodb/README.md` (WriteConcern 안내 포함)
- [ ] `leader-mongodb/README.ko.md`
- [ ] 루트 README 백엔드 표 MongoDB 추가
- [ ] `CHANGELOG.md`
- [ ] `CLAUDE.md` `(planned)` 제거

### 10.4 PR 전 6중 코드 리뷰

- [ ] bluetape4k-design Step 6-R 6-Tier 실행
- [ ] CRITICAL/HIGH 0 확인 후 PR 생성

---

## 11. 참고

- 기존 패턴: `leader-hazelcast/...lock/HazelcastLock.kt`
- 기존 패턴: `leader-hazelcast/.../HazelcastLeaderElection.kt`
- 기존 패턴: `leader-hazelcast/.../HazelcastSuspendLeaderElection.kt`
- `mongodb-driver-kotlin-coroutine`: https://www.mongodb.com/docs/drivers/kotlin/coroutine/current/
