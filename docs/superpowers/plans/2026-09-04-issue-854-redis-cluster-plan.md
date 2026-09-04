# Issue #854 Redis Cluster 공식 지원 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `leader-redis-lettuce`의 네 가지 strategic elector가 standalone Redis와 Redis Cluster를 동일한 후보·TTL·결과 계약으로 지원하도록 하고, hash-slot-safe key layout, v2/colon legacy migration, 실제 Cluster/Testcontainers 검증, ABI/API 및 양국어 문서를 완성한다.

**Architecture:** 후보 key는 v3에서 lockName을 공통 hash tag로 감싸 index와 candidate multi-key 연산을 한 slot에 고정한다. blocking/suspend registry는 호출자가 소유한 standalone/Cluster connection을 각각의 Lettuce capability adapter로 감싸고, v3 후보만 batch read와 same-slot Lua를 사용한다. v2와 colon legacy source는 단일 key GET/PTTL로 읽어 CROSSSLOT을 피하고, v3 destination의 `SET NX`·index repair·lifecycle fence·migration token을 same-slot Lua로 원자화한다. unregister는 persistent tombstone으로 source resurrection을 차단하고, cleanup은 raw value와 unique token을 함께 비교한다. 네 public elector에는 기존 standalone JVM descriptor를 보존하는 명시적 Cluster secondary constructor를 추가한다.

**Tech Stack:** Kotlin/JVM, Lettuce `7.6.0.RELEASE` (현재 catalog resolved version), Redis Cluster, JUnit 5, bluetape assertions, kotlinx-coroutines, Testcontainers `RedisClusterServer.Launcher.redisCluster`, Gradle `clusterTest`, Kover, detekt, binary API checker.

---

## 실행 상태 체크박스

- [x] Task 1 — key codec와 slot 불변식
- [x] Task 2 — scripting capability와 same-slot write Lua
- [x] Task 3 — blocking registry capability adapter와 versioned precedence
- [x] Task 4 — suspend registry parity와 cancellation
- [x] Task 5 — 네 public elector의 additive Cluster API와 ABI
- [x] Task 6 — 실제 Redis Cluster fixture와 전용 `clusterTest`
- [x] Task 7 — README locale과 Nightly/manual CI gate
- [x] Task 8 — review artifact, lesson, full verification

## 0. 고정 전제와 실행 경계

- 기준은 `origin/develop` `c47324bcff5738e5505cf733d838afd730bad226`이며 작업 branch/worktree는 `feat/issue-854-redis-cluster` / `.worktrees/feat/issue-854-redis-cluster`다.
- 이 계획은 revised 설계를 실행 단위로 분해한다. resolved version·standalone ABI·lifecycle fence·migration token·Cluster CI guard 보정은 material design change이므로 이전 spec/plan 승인을 재사용하지 않고 fresh 사용자 승인을 받은 뒤에만 Task 1 구현을 시작한다.
- `StatefulRedisConnection` 및 `StatefulRedisClusterConnection`은 호출자 소유다. registry/elector가 connection, client, container를 닫지 않는다.
- 기존 `indexKey`/`candidateKey` 호출자는 v3를 사용한다. 기존 v2 및 colon key는 읽기·등록 migration source로만 유지하며 v3 write 이후 구버전 writer rollback은 지원하지 않는다. v3에는 node별 persistent tombstone/fence와 migration token key를 추가해 unregister→migration resurrection과 동일 payload cleanup race를 막는다.
- 현재 dependency catalog immutable ref `850959d0ea5f76ac7e2c442400f47653d5f95eed`가 Lettuce `7.6.0.RELEASE`를 resolve한다. 이 이슈는 전역 catalog나 7.7 dependency upgrade를 포함하지 않으며, 구현 후 `dependencyInsight` 결과를 evidence로 남긴다.
- `lockName` validation은 기존 `validateLockName`을 그대로 사용한다. `{}`는 hash-tag 경계 모호성을 없애기 위해 계속 거부한다.
- Redis Cluster failover 성능 최적화와 별도 benchmark는 범위 밖이다. MOVED/ASK/topology refresh가 수렴하지 않는 외부 장애는 지원 계약의 성공으로 간주하지 않는다.
- PR, push, merge, tag, release, branch/worktree 삭제와 Nightly dispatch는 구현·검증과 분리한다. 이 계획의 끝은 merge-ready 증거를 갖춘 로컬 상태다.

## 1. 파일 소유와 변경 지도

| 경로 | 작업 | 책임 |
|---|---|---|
| `leader-redis-lettuce/src/main/kotlin/io/bluetape4k/leader/lettuce/LettuceCandidateKeyCodec.kt` | 수정 | v3 hash-tag key와 v2/legacy source codec |
| `leader-redis-lettuce/src/main/kotlin/io/bluetape4k/leader/lettuce/script/RedisScript.kt` | 수정 | sync/async scripting capability 공통 overload와 `NOSCRIPT` 계약 |
| `leader-redis-lettuce/src/main/kotlin/io/bluetape4k/leader/lettuce/LettuceCandidateWriteScript.kt` | 신규 | register 및 same-slot migration write Lua |
| `leader-redis-lettuce/src/main/kotlin/io/bluetape4k/leader/lettuce/LettuceCandidateRefreshScript.kt` | 수정 | v3 same-slot refresh 결과와 TTL 상태 |
| `leader-redis-lettuce/src/main/kotlin/io/bluetape4k/leader/lettuce/LettuceCandidateIndexCleanupScript.kt` | 수정 | v3 candidate 존재 확인 후 stale index 정리 |
| `leader-redis-lettuce/src/main/kotlin/io/bluetape4k/leader/lettuce/LettuceCandidateCommands.kt` | 신규 | blocking direct/script capability와 standalone/Cluster value reader |
| `leader-redis-lettuce/src/main/kotlin/io/bluetape4k/leader/lettuce/LettuceCandidateRegistry.kt` | 수정 | blocking capability adapter, v3 read/migration/precedence |
| `leader-redis-lettuce/src/main/kotlin/io/bluetape4k/leader/lettuce/LettuceSuspendCandidateCommands.kt` | 신규 | suspend direct/script capability와 standalone/Cluster coroutine value reader |
| `leader-redis-lettuce/src/main/kotlin/io/bluetape4k/leader/lettuce/LettuceSuspendCandidateRegistry.kt` | 수정 | suspend capability adapter와 blocking parity |
| `leader-redis-lettuce/src/main/kotlin/io/bluetape4k/leader/lettuce/LettuceStrategicLeaderElector.kt` | 수정 | standalone descriptor 보존 및 Cluster constructor |
| `leader-redis-lettuce/src/main/kotlin/io/bluetape4k/leader/lettuce/LettuceStrategicLeaderGroupElector.kt` | 수정 | group standalone descriptor 보존 및 Cluster constructor |
| `leader-redis-lettuce/src/main/kotlin/io/bluetape4k/leader/lettuce/LettuceStrategicSuspendLeaderElector.kt` | 수정 | suspend standalone descriptor 보존 및 Cluster constructor |
| `leader-redis-lettuce/src/main/kotlin/io/bluetape4k/leader/lettuce/LettuceStrategicSuspendLeaderGroupElector.kt` | 수정 | suspend group standalone descriptor 보존 및 Cluster constructor |
| `leader-redis-lettuce/src/test/kotlin/io/bluetape4k/leader/lettuce/LettuceCandidateKeyCodecTest.kt` | 신규 | v3/v2 key, UTF-8 length, slot 불변식, brace validation |
| `leader-redis-lettuce/src/test/kotlin/io/bluetape4k/leader/lettuce/LettuceCandidateKeyIsolationTest.kt` | 수정 | v2/legacy migration과 v3 precedence 회귀 |
| `leader-redis-lettuce/src/test/kotlin/io/bluetape4k/leader/lettuce/LettuceCandidateWriteScriptTest.kt` | 신규 | write Lua 반환 상태와 TTL 경계 |
| `leader-redis-lettuce/src/test/kotlin/io/bluetape4k/leader/lettuce/LettuceStrategicRedisClusterTest.kt` | 신규 | 기존 Launcher 기반 Cluster fixture, 네 elector lifecycle, MGET/Lua/CROSSSLOT/migration matrix |
| `leader-redis-lettuce/build.gradle.kts` | 수정 | `clusterTest` tagged Test task |
| `.github/workflows/nightly-tests.yml` | 수정 | Cluster 전용 Nightly job, artifact, aggregator needs |
| `leader-redis-lettuce/README.md` | 수정 | English Cluster constructor/운영 경계 |
| `leader-redis-lettuce/README.ko.md` | 수정 | Korean Cluster constructor/운영 경계 |
| `docs/review/2026-09-04-issue-854-redis-cluster-review.md` | 신규 | 7-Tier pre-PR review와 P0/P1 수렴 |
| `docs/lessons/2026-09-04-issue-854-redis-cluster.md` | 신규 | 재사용 가능한 key/migration/fixture lesson |
| `.flow-inputs/issue-854-workflow/2026-09-04-checklist.md` | 갱신 | 각 gate의 fresh command/count/evidence |

소유권은 main lane에 고정한다. 독립 subagent는 계획·최종 diff의 read-only 검토만 수행하며 위 파일을 수정하지 않는다.

## 2. Acceptance traceability

| 승인된 acceptance | 구현 작업 | 검증 증거 |
|---|---|---|
| 네 strategic elector가 standalone/Cluster를 지원하고 기존 descriptor가 유지됨 | Task 5 | `javap`, binary API checker, Kotlin/Java consumer compile, Cluster matrix |
| v3 index/candidate가 동일 slot이고 v2/colon key collision이 없음 | Task 1, 3 | codec unit, `SlotHash`, 실제 Cluster multi-key/MGET, `CROSSSLOT` 부재 |
| v3 register/refresh/result/index cleanup이 same-slot 원자 경계를 지킴 | Task 2, 3 | Lua unit/Cluster 실행, concurrent reader, stale-cleanup |
| v2와 colon source가 v3보다 낮은 precedence로 migration됨 | Task 3, 4 | source precedence, concurrent migration race, destination repair, old source 보존 |
| unregister가 source migration으로 되살아나지 않고 cleanup이 다른 writer를 삭제하지 않음 | Task 2, 3, 6 | persistent tombstone barrier, migration token+raw compare, deterministic race/ownership tests |
| TTL 0/positive/expired 경계와 결과 통계 보존 | Task 2–4 | PTTL/SET NX tests, heartbeat tests, post-copy expiry race |
| blocking/suspend가 command/cancellation 의미를 보존함 | Task 3, 4 | 네 elector lifecycle 및 cancellation tests |
| 실제 Cluster fixture가 Nightly/manual 전용으로 실행됨 | Task 6, 7 | `clusterTest`, fixture image/ref/digest, cluster_state/endpoints/logs artifact |
| 문서·호환성·운영 rollout 경계가 코드와 일치함 | Task 7, 8 | README locale diff, review artifact, `git diff --check` |
| P0/P1이 0이고 release/PR 경계가 분리됨 | Task 8 | 7-Tier review, checklist counts, no push/PR evidence |

## 3. Task 1 — key codec와 slot 불변식 (RED → GREEN)

**Files:** `LettuceCandidateKeyCodec.kt`, 신규 `LettuceCandidateKeyCodecTest.kt`, 기존 `LettuceCandidateKeyIsolationTest.kt`.

1. `[ ]` RED: 다음 테스트를 먼저 추가한다. `io.bluetape4k.assertions`와 기존 프로젝트 test idiom을 사용하고, 테스트 이름은 동작을 명시한다.

   ```kotlin
   class LettuceCandidateKeyCodecTest {
       @Test
       fun `v3 index and candidate keys share the lock hash slot`() {
           val prefix = "leader:strategy:candidates"
           val lockName = "결정:lock"
           val nodeId = "node:1"
           val index = LettuceCandidateKeyCodec.indexKey(prefix, lockName)
           val candidate = LettuceCandidateKeyCodec.candidateKey(prefix, lockName, nodeId)

           index shouldBe "leader:strategy:candidates|v3|i|{11:결정:lock}"
           candidate shouldBe "leader:strategy:candidates|v3|c|{11:결정:lock}6:node:1"
           SlotHash.getSlot(StringCodec.UTF8.encodeKey(index)) shouldBe
               SlotHash.getSlot(StringCodec.UTF8.encodeKey(candidate))
       }

       @Test
       fun `v2 and colon source keys remain addressable without sharing v3 namespace`() {
           val prefix = "leader:strategy:candidates"
           val lockName = "a:b"
           val nodeId = "n:1"

           LettuceCandidateKeyCodec.v2IndexKey(prefix, lockName) shouldBe
               "leader:strategy:candidates|v2|i|3:a:b"
           LettuceCandidateKeyCodec.v2CandidateKey(prefix, lockName, nodeId) shouldBe
               "leader:strategy:candidates|v2|c|3:a:b3:n:1"
           LettuceCandidateKeyCodec.legacyCandidateKey(prefix, lockName, nodeId) shouldBe
               "leader:strategy:candidates:a:b:n:1"
       }

       @Test
       fun `lock names containing braces remain rejected`() {
           assertFailsWith<IllegalArgumentException> { validateLockName("a{b") }
           assertFailsWith<IllegalArgumentException> { validateLockName("a}b") }
       }
   }
   ```

2. `[ ]` RED 검증: `./gradlew :bluetape4k-leader-redis-lettuce:test --tests '*LettuceCandidateKeyCodecTest*' --no-daemon --no-configuration-cache --no-build-cache --console=plain`을 실행한다. 구현 전에는 v2 문자열/누락된 helper 때문에 실패해야 하며, compile 환경 실패는 RED 증거로 인정하지 않는다.
3. `[ ]` GREEN 구현: codec의 버전을 `v3`으로 전환하고 다음 API를 유지한다. `lengthDelimited`는 UTF-8 byte length를 사용한다.

   ```kotlin
   internal object LettuceCandidateKeyCodec {
       private const val CURRENT_VERSION = "v3"
       private const val PREVIOUS_VERSION = "v2"
       private const val INDEX_TYPE = "i"
       private const val CANDIDATE_TYPE = "c"
       private const val NAMESPACE_SEPARATOR = "|"

       fun indexKey(keyPrefix: String, lockName: String): String =
           "$keyPrefix|$CURRENT_VERSION|$INDEX_TYPE|{" + lengthDelimited(lockName) + "}"

       fun candidateKey(keyPrefix: String, lockName: String, nodeId: String): String =
           "$keyPrefix|$CURRENT_VERSION|$CANDIDATE_TYPE|{" +
               lengthDelimited(lockName) + "}" + lengthDelimited(nodeId)

       fun tombstoneKey(keyPrefix: String, lockName: String, nodeId: String): String =
           "$keyPrefix|$CURRENT_VERSION|t|{" +
               lengthDelimited(lockName) + "}" + lengthDelimited(nodeId)

       fun migrationTokenKey(keyPrefix: String, lockName: String, nodeId: String): String =
           "$keyPrefix|$CURRENT_VERSION|m|{" +
               lengthDelimited(lockName) + "}" + lengthDelimited(nodeId)

       internal fun v2IndexKey(keyPrefix: String, lockName: String): String =
           "$keyPrefix|$PREVIOUS_VERSION|$INDEX_TYPE|" + lengthDelimited(lockName)

       internal fun v2CandidateKey(keyPrefix: String, lockName: String, nodeId: String): String =
           "$keyPrefix|$PREVIOUS_VERSION|$CANDIDATE_TYPE|" +
               lengthDelimited(lockName) + lengthDelimited(nodeId)

       fun legacyIndexKey(keyPrefix: String, lockName: String): String = "$keyPrefix:$lockName"

       fun legacyCandidateKey(keyPrefix: String, lockName: String, nodeId: String): String =
           "${legacyIndexKey(keyPrefix, lockName)}:$nodeId"

       private fun lengthDelimited(value: String): String {
           val byteLength = value.toByteArray(StandardCharsets.UTF_8).size
           return "$byteLength:$value"
       }
   }
   ```

   `NAMESPACE_SEPARATOR`는 기존 코드 가독성을 위해 유지하되 key 조합은 exact string으로 검증한다. `{lengthDelimited(lockName)}`만 hash tag이며 nodeId는 tag 밖이다. tombstone/token helper도 같은 tag를 공유하고, v2/legacy helper는 migration fixture 전용으로 유지한다.
4. `[ ]` GREEN 검증: 위 targeted test가 `BUILD SUCCESSFUL` 및 모든 assertion pass를 출력해야 한다. 기존 key-isolation tests의 v2 fixture helper를 `v2IndexKey`/`v2CandidateKey`로 바꾸고 전체 isolation test도 pass시킨다.
5. `[ ]` 커밋 시점에는 Korean Lore intent/trailers를 사용하고, plan/checklist의 evidence를 즉시 갱신한다. 커밋은 별도 사용자 PR/merge 승인으로 간주하지 않는다.

## 4. Task 2 — scripting capability와 same-slot write Lua (RED → GREEN)

**Files:** `RedisScript.kt`, 신규 `LettuceCandidateWriteScript.kt`, 신규 `LettuceCandidateWriteScriptTest.kt`, `LettuceCandidateRefreshScript.kt`, `LettuceCandidateIndexCleanupScript.kt`.

1. `[ ]` RED: script runner의 standalone/Cluster capability compile surface와 `NOSCRIPT` fallback을 고정한다. 공통 overload의 외부 계약은 다음과 같다.

   ```kotlin
   fun <T> run(
       commands: RedisScriptingCommands<String, String>,
       script: RedisScript,
       outputType: ScriptOutputType,
       keys: Array<String>,
       vararg args: String,
   ): T

   fun <T> runAsync(
       commands: RedisScriptingAsyncCommands<String, String>,
       script: RedisScript,
       outputType: ScriptOutputType,
       keys: Array<String>,
       vararg args: String,
   ): CompletableFuture<T>

   suspend fun <T> runSuspending(
       commands: RedisScriptingAsyncCommands<String, String>,
       script: RedisScript,
       outputType: ScriptOutputType,
       keys: Array<String>,
       vararg args: String,
   ): T
   ```

   기존 `RedisCommands`/`RedisAsyncCommands` overload와 `evalsha` 우선, `RedisNoScriptException`일 때 원문 fallback, 그 외 예외 cause 보존, suspend cancellation 전파를 유지한다. 공통 구현은 private helper로 수렴하고 public overload descriptor는 삭제하지 않는다.

2. `[ ]` RED 검증: 신규 runner test 또는 compile-only contract test가 overload 부재로 실패하는지 확인한다. 가짜 `CROSSSLOT` 예외를 성공으로 처리하지 말고 실제 Cluster test에서 재검증한다.
3. `[ ]` GREEN 구현: `LettuceCandidateWriteScript`에 register, migrate, unregister, token-guarded cleanup script를 정의한다. 모든 multi-key script는 v3 candidate/index/tombstone/token만 `KEYS`로 받고 호출자는 네 key가 같은 tag임을 먼저 보장한다. v2/colon source는 어떤 script에도 전달하지 않는다.

   ```kotlin
   internal object LettuceCandidateWriteScript {
       const val ABSENT = 0L
       const val WRITTEN = 1L
       const val EXISTING_REPAIRED = 2L

       val REGISTER = RedisScript(
           """
           local ttl = tonumber(ARGV[2])
           if ttl and ttl < 0 then
             return redis.error_reply('ERR invalid candidate TTL')
           end
           redis.call('DEL', KEYS[3], KEYS[4])
           if ttl and ttl > 0 then
             redis.call('PSETEX', KEYS[1], ttl, ARGV[1])
           else
             redis.call('SET', KEYS[1], ARGV[1])
           end
           redis.call('SADD', KEYS[2], ARGV[3])
           redis.call('PERSIST', KEYS[2])
           return { $WRITTEN }
           """.trimIndent(),
       )

       val MIGRATE = RedisScript(
           """
           if redis.call('EXISTS', KEYS[3]) == 1 then
             return { $ABSENT }
           end
           local ttl = tonumber(ARGV[2])
           if not ttl or ttl == -2 or ttl <= 0 and ttl ~= -1 then
             return { $ABSENT }
           end
           local written
           if ttl == -1 then
             written = redis.call('SET', KEYS[1], ARGV[1], 'NX')
           else
             written = redis.call('SET', KEYS[1], ARGV[1], 'NX', 'PX', ttl)
           end
           if not written then
             if redis.call('EXISTS', KEYS[1]) == 1 then
               redis.call('SADD', KEYS[2], ARGV[3])
               redis.call('PERSIST', KEYS[2])
               return { $EXISTING_REPAIRED }
             end
             return { $ABSENT }
           end
           redis.call('SADD', KEYS[2], ARGV[3])
           redis.call('PERSIST', KEYS[2])
           redis.call('SET', KEYS[4], ARGV[4])
           return { $WRITTEN }
           """.trimIndent(),
       )

       val UNREGISTER = RedisScript(
           """
           redis.call('SET', KEYS[3], ARGV[1])
           redis.call('DEL', KEYS[1], KEYS[4])
           redis.call('SREM', KEYS[2], ARGV[2])
           return 1
           """.trimIndent(),
       )

       val REMOVE_IF_VALUE = RedisScript(
           """
           if redis.call('GET', KEYS[1]) ~= ARGV[1] then
             return 0
           end
           if redis.call('GET', KEYS[3]) ~= ARGV[3] then
             return 0
           end
           local removed = redis.call('DEL', KEYS[1], KEYS[3])
           redis.call('SREM', KEYS[2], ARGV[2])
           return removed
           """.trimIndent(),
       )
   }
   ```

   `MIGRATE`는 candidate/index/tombstone/token 순서로, `REMOVE_IF_VALUE`는 candidate/index/token 순서로 key를 전달한다. 성공적인 `SET NX` 때만 `ARGV[4]` unique token을 persistent하게 기록하고, destination이 이미 있으면 token을 탈취하지 않고 index를 `SADD`/`PERSIST`로 repair한 뒤 `EXISTING_REPAIRED`를 반환한다. `UNREGISTER`는 tombstone을 먼저 persistent하게 세워 migration을 차단한 뒤 candidate/token/index를 정리한다. `ttl == 0`은 migration에서는 source 부재/만료로 취급하며, register의 `ttl == 0`만 persistent다. negative TTL은 호출자 validation과 기존 예외 계약을 유지한다.
   Migration source key는 Lua `KEYS`에 넣지 않는다. source GET/PTTL은 single-key command로 수행하고, 복사 대상 v3 key만 same-slot Lua에 넣는다. source read 직후 unregister가 실행되는 barrier race에서는 Redis script 원자 순서에 따라 tombstone이 migration을 거부하거나 unregister가 destination을 제거한다. raw value만 비교해 삭제하지 않고 token과 함께 비교해 동일 payload를 다시 쓴 writer를 보호한다.
4. `[ ]` GREEN 구현: refresh/result/cleanup script 호출은 v3 key만 받도록 정리한다. refresh는 candidate/index/token, result는 candidate/token을 same-slot `KEYS`로 받아 regular writer가 migration token을 해제한다. malformed payload는 기존 `LettuceCandidateInfoCodec` 예외를 그대로 다시 던지고, absent candidate를 새로 생성하지 않는다. register는 tombstone/token을 먼저 지우고, migration은 tombstone 존재 시 `ABSENT`, 성공 시 unique token, 기존 destination 시 `EXISTING_REPAIRED`를 반환하며, unregister는 persistent tombstone을 세운다.
5. `[ ]` GREEN 검증: write script unit/fixture test에서 persistent, positive PTTL, `-1`, `-2`, non-positive 경계, tombstone refusal, unregister ordering, token+raw compare cleanup 및 same-payload writer 보호를 확인한다. `git diff --check`를 실행하고 script return status와 exception cause를 checklist에 기록한다.

## 5. Task 3 — blocking registry capability adapter와 versioned precedence

**Files:** `LettuceCandidateRegistry.kt`, `LettuceCandidateKeyIsolationTest.kt`, `LettuceStrategicHeartbeatTest.kt`.

1. `[ ]` RED: standalone/Cluster constructor compile surface와 다음 precedence를 검증하는 테스트를 먼저 추가한다.

   ```kotlin
   // v3 값이 있으면 v2/colon 값이 달라도 v3만 반환한다.
   registry.registerCandidate(lockName, v3Info, Duration.ZERO)
   standalone.sync().set(v2Key, encodedV2)
   standalone.sync().set(legacyKey, encodedLegacy)
   registry.listCandidates(lockName).single() shouldBe v3Info

   // v3가 없으면 v2가 colon legacy보다 우선하고, destination/index를 repair한다.
   standalone.sync().del(v3Key, v3Index)
   registry.listCandidates(lockName).single() shouldBe v2Info
   standalone.sync().smembers(v3Index) shouldContain nodeId
   ```

2. `[ ]` RED 검증: 기존 registry가 standalone connection만 수용하므로 Cluster constructor test가 compile 실패하거나 v3 precedence assertion이 실패해야 한다.
3. `[ ]` GREEN 설계: blocking registry는 caller-facing constructor 두 개와 private narrow adapter를 사용한다. adapter는 single-key/set/script를 `RedisCommands`/`RedisClusterCommands`의 실제 capability에 위임하고, batch reader만 별도로 둔다.

   ```kotlin
   internal class LettuceCandidateRegistry private constructor(
       private val commands: BlockingCandidateCommands,
       private val readMany: CandidateValueReader,
       private val keyPrefix: String,
   ) {
       // 기존 internal one-argument call site/fixture를 위한 compatibility bridge
       internal constructor(
           commands: BlockingCandidateCommands,
           keyPrefix: String = DEFAULT_KEY_PREFIX,
       ) : this(
           commands,
           CandidateValueReader { keys -> keys.associateWith { commands.get(it) } },
           keyPrefix,
       )

       constructor(
           connection: StatefulRedisConnection<String, String>,
           keyPrefix: String = DEFAULT_KEY_PREFIX,
       ) : this(
           StandaloneBlockingCandidateCommands(connection.sync()),
           StandaloneCandidateValueReader(connection.sync()),
           keyPrefix,
       )

       constructor(
           connection: StatefulRedisClusterConnection<String, String>,
           keyPrefix: String = DEFAULT_KEY_PREFIX,
       ) : this(
           ClusterBlockingCandidateCommands(connection.sync()),
           ClusterCandidateValueReader(connection.sync()),
           keyPrefix,
       )
   }

   private fun interface CandidateValueReader {
       fun read(keys: List<String>): Map<String, String?>
   }

   private class StandaloneCandidateValueReader(
       private val commands: RedisCommands<String, String>,
   ) : CandidateValueReader {
       override fun read(keys: List<String>): Map<String, String?> =
           keys.associateWith { commands.get(it) }
   }

   private class ClusterCandidateValueReader(
       private val commands: RedisAdvancedClusterCommands<String, String>,
   ) : CandidateValueReader {
       override fun read(keys: List<String>): Map<String, String?> =
           commands.mget(*keys.toTypedArray()).associate { value ->
               value.key to value.value
           }
   }
   ```

   실제 Lettuce 타입이 제공하는 공통 parent가 다르면 adapter 내부에서만 concrete type을 조정한다. public constructor와 script runner overload는 변경하지 않는다. standalone과 Cluster `MGET`은 v3 candidate keys만 호출하며, Cluster key는 모두 동일 lockName tag를 가진다. v2/colon source는 단건 `GET`/`PTTL`로만 읽는다. 기존 internal one-argument registry 호출자는 compatibility bridge를 통해 같은 command/read capability를 유지한다.

4. `[ ]` GREEN 구현 세부:
   - `registerCandidate`: v3 candidate/index를 `REGISTER` Lua로 기록한다. index TTL은 항상 `PERSIST`한다.
   - `listCandidates`: v3 index `SMEMBERS` → `CandidateValueReader.read` → decode/expected nodeId 확인 → stale/mismatch cleanup 순서로 실행한다. v3가 없거나 node가 빠졌을 때 v2 index/candidate를 single-key로 읽고, 마지막으로 colon index/candidate를 읽는다. node별 tombstone이 있으면 해당 source candidate를 숨기고 migration하지 않는다.
   - source precedence는 `v3 > v2 > colon legacy`이며 malformed payload는 다음 source로 조용히 fall through하지 않는다. mismatch는 기존 stale-index 정리 계약을 따른다.
   - migration은 source candidate의 `GET` 및 `PTTL`을 각각 single-key로 관찰한 뒤 destination candidate/index/tombstone/token만 `MIGRATE` Lua에 전달한다. tombstone이 생기면 `ABSENT`, `ttl == -1`은 `SET NX`, positive는 관찰 PTTL 이하의 `SET NX PX`, `-2`/`<=0`은 skip한다. 성공한 호출만 unique token을 저장한다.
   - copy 후 source를 다시 `GET`/`PTTL`하여 만료가 확인되고 destination write가 이 호출에서 성공한 경우에만 destination raw value와 migration token이 모두 일치할 때 same-slot `REMOVE_IF_VALUE`로 best-effort cleanup을 수행한다. 다른 writer가 같은 payload를 다시 썼거나 token을 지웠다면 삭제하지 않는다. source key는 절대 삭제하지 않으며 bounded stale window를 문서화한다.
   - `refreshCandidate`와 `updateResult`는 v3 destination이 없을 때 node tombstone을 확인한 뒤 v2 → colon 순서로 migration을 시도하고 v3 same-slot script를 호출한다. v3 destination을 갱신할 때는 migration token을 함께 지워 stale cleanup ownership을 해제한다. `unregisterCandidate`는 v3 `UNREGISTER` Lua로 tombstone 설정·v3 candidate/token/index 정리를 원자화한 후 v2/colon source와 각 index member를 single-key로 정리한다.
   - deterministic barrier test는 source GET/PTTL 이후 `unregisterCandidate`를 실행하고 migration을 재개한다. 결과는 tombstone 유지, v3 destination/index 부재, source 비노출이며, 이후 `registerCandidate`가 tombstone을 지우고 새 generation을 시작하는 것도 확인한다.

5. `[ ]` GREEN 검증:
   - `./gradlew :bluetape4k-leader-redis-lettuce:test --tests '*LettuceCandidateKeyIsolationTest*' --tests '*LettuceStrategicHeartbeatTest*' --no-daemon --no-configuration-cache --no-build-cache --console=plain`이 pass한다.
   - 테스트에서 v3/v2/colon collision, v2 TTL 보존, concurrent `SET NX` winner 하나, stale index cleanup, result counter 보존을 확인한다.
   - source와 destination이 같은 raw payload인 상태에서 새 register/refresh가 token을 교체한 뒤 이전 cleanup을 호출해도 destination/index가 보존되는지 확인한다. migration Lua의 `KEYS`에 source key가 없고 실제 Cluster에서 `CROSSSLOT`이 발생하지 않는지도 검증한다.

## 6. Task 4 — suspend registry parity와 cancellation

**Files:** `LettuceSuspendCandidateRegistry.kt`, `LettuceStrategicSuspendLeaderElector.kt`, `LettuceStrategicSuspendLeaderGroupElector.kt`, suspend lifecycle tests.

1. `[ ]` RED: `StatefulRedisClusterConnection.coroutines()`와 `async()`를 사용하는 suspend constructor/matrix test 및 cancellation test를 추가한다. cancellation 중 direct command와 script future가 각각 취소되고 `CancellationException`이 삼켜지지 않아야 한다. in-flight migration, refresh, result update, `runIfLeader` action cancellation을 별도 test method로 고정한다.
2. `[ ]` GREEN 구현: blocking adapter와 동일한 key/precedence/migration 알고리즘을 suspend command로 구현한다. tombstone refusal, token-guarded cleanup, source preservation을 blocking path와 동일하게 유지한다.

   ```kotlin
   @file:OptIn(ExperimentalLettuceCoroutinesApi::class)

   private constructor(
       private val commands: SuspendCandidateCommands,
       private val readMany: SuspendCandidateValueReader,
       private val keyPrefix: String,
   )

   constructor(
       connection: StatefulRedisConnection<String, String>,
       keyPrefix: String = DEFAULT_KEY_PREFIX,
   ) : this(
       StandaloneSuspendCandidateCommands(connection.coroutines(), connection.async()),
       StandaloneSuspendCandidateValueReader(connection.coroutines()),
       keyPrefix,
   )

   constructor(
       connection: StatefulRedisClusterConnection<String, String>,
       keyPrefix: String = DEFAULT_KEY_PREFIX,
   ) : this(
       ClusterSuspendCandidateCommands(connection.coroutines(), connection.async()),
       ClusterSuspendCandidateValueReader(connection.coroutines()),
       keyPrefix,
   )
   ```

   direct command는 `RedisClusterCoroutinesCommands`, script는 `RedisScriptingAsyncCommands`로 분리한다. standalone `RedisCoroutinesCommands`는 adapter에서 직접 위임한다. script는 `RedisScriptRunner.runSuspending`와 `await()`를 사용하고, `CancellationException`은 기존 `updateStrategicResultPreservingCancellation` 경계를 통해 재전파한다.
3. `[ ]` GREEN 검증: blocking과 동일한 fixture에 대해 suspend single/group register, list, refresh, updateResult, migration, stale cleanup을 실행한다. `runIfLeader` action cancellation, direct command cancellation, script future cancellation을 별도 assertion으로 확인하고 cause가 `CancellationException`인지 검증한다. unregister/migration barrier와 same-payload token ownership도 suspend에서 확인한다.
4. `[ ]` 정적 검증: 새 `!!`, `runBlocking` production 사용, cancellation catch 후 무시, caller connection close가 없는지 `rg`와 detekt로 확인한다.

## 7. Task 5 — 네 public elector의 additive Cluster API와 ABI

**Files:** 네 `LettuceStrategic*Elector.kt`, 관련 contract tests, consumer compile fixture.

1. `[ ]` RED: 기준 develop artifact에서 네 클래스의 `javap -p -s` descriptor set을 저장하고, precompiled Kotlin one-argument consumer와 Java two-argument consumer가 현재 standalone constructor를 호출하는지 확인한다. 네 클래스 각각에 Cluster one-/two-argument constructor reflection/compile test를 추가한다. 기존 standalone descriptor와 default nodeId 동작도 함께 고정한다.
2. `[ ]` GREEN 구현: primary constructor를 private registry/nodeId 형태로 바꾸고 다음 명시적 public overload를 네 클래스에 각각 적용한다. Group 클래스는 `GROUP_KEY_PREFIX`를 계속 사용한다.

   ```kotlin
   class LettuceStrategicLeaderElector private constructor(
       private val registry: LettuceCandidateRegistry,
       override val nodeId: String,
   ) : StrategicLeaderElector {
       @JvmOverloads
       constructor(
           connection: StatefulRedisConnection<String, String>,
           nodeId: String = Uuid.V7.nextBase62(),
       ) : this(LettuceCandidateRegistry(connection), nodeId)

       @JvmOverloads
       constructor(
           connection: StatefulRedisClusterConnection<String, String>,
           nodeId: String = Uuid.V7.nextBase62(),
       ) : this(LettuceCandidateRegistry(connection), nodeId)
   }
   ```

   Group/suspend/suspend-group도 registry 종류와 interface만 바꾼 동일한 descriptor 집합을 갖는다. standalone secondary constructor의 `@JvmOverloads`와 Kotlin default synthetic bridge를 의도적으로 유지하고, Cluster overload는 명시적 새 descriptor로 추가한다. `javap -p -s` 결과는 기준과 after를 diff하며, compatibility checker가 synthetic descriptor를 생략해도 precompiled Kotlin consumer compile을 별도 필수 증거로 삼는다. `RedisScriptRunner`의 기존 overload도 standalone/Cluster capability 각각에 대해 descriptor와 consumer compile을 별도로 확인한다.
3. `[ ]` GREEN 검증:
   - `./gradlew :bluetape4k-leader-redis-lettuce:compileKotlin :bluetape4k-leader-redis-lettuce:compileTestKotlin --no-daemon --no-configuration-cache --no-build-cache --console=plain`
   - `./gradlew checkBinaryCompatibility --no-daemon --no-configuration-cache --no-build-cache --console=plain`
   - `python3 scripts/compatibility/check_binary_api.py`
   - 기준/after artifact에 `javap -p -s`를 실행해 네 elector와 `RedisScriptRunner`의 old/new descriptor를 비교한다.
   - precompiled Kotlin fixture가 standalone one-/two-arg 및 Cluster one-/two-arg constructor를 compile하고, Java fixture가 명시적 public descriptor를 compile한다.
4. `[ ]` ABI 결과를 이전 develop baseline과 비교한다. 삭제/변경 descriptor가 있거나 Kotlin fixture가 synthetic bridge를 호출하지 못하면 Task 5를 멈추고 compatibility repair 후 같은 명령을 다시 실행한다.

## 8. Task 6 — 실제 Redis Cluster fixture와 전용 `clusterTest`

**Files:** 신규 `LettuceStrategicRedisClusterTest.kt`, `leader-redis-lettuce/build.gradle.kts`.

1. `[ ]` RED: `@Tag("redis-cluster")` 테스트와 전용 task를 먼저 추가한다. regular `test`가 이 tag를 실행하지 않는지 확인한다.
2. `[ ]` GREEN fixture는 `bluetape4k-testcontainers:2.1.0-SNAPSHOT`의 기존 Testcontainers helper와 기본 image `tommy351/redis-cluster:6.2`를 재사용한다. 새 raw container를 만들지 않는다. 테스트 task가 시작할 때 `build/redis-cluster-diagnostics/`를 먼저 만들고 fixture는 provenance를 즉시 기록하므로, startup failure에서도 artifact 경로가 사라지지 않는다.

   ```kotlin
   abstract class AbstractLettuceClusterTest {
       companion object {
           @JvmStatic
           protected val redisCluster = RedisClusterServer.Launcher.redisCluster

           @JvmStatic
           protected val clusterClient by lazy {
               RedisClusterServer.Launcher.LettuceLib.getClusterClient(redisCluster)
           }

           @JvmStatic
           protected val clusterConnection by lazy {
               clusterClient.connect(StringCodec.UTF8).also { connection ->
                   ShutdownQueue.register { connection.closeSafe() }
               }
           }
       }
   }
   ```

   fixture image/ref/digest, cluster state, node endpoints, topology refresh, container logs/events는 startup 후 provenance 파일과 CI failure hook에서 수집한다. 현재 테스트는 `LettuceStrategicRedisClusterTest` 내부 helper로 기존 Launcher를 감싸며, task/CI가 provenance·JUnit XML·failure diagnostics를 검증한다. 테스트 시작 시 실제 image reference와 digest를 read-back하고 `CLUSTER INFO`의 `cluster_state:ok`, `CLUSTER NODES` endpoint를 기록한다. registry/elector는 connection을 닫지 않고, helper의 `use` 정리만 사용한다.
3. `[x]` 테스트 matrix를 다음 순서로 구현한다.
   - blocking single elector: `DEFAULT_KEY_PREFIX`, register/list/refresh/result/unregister.
   - blocking group elector: `GROUP_KEY_PREFIX`, top-N list와 same-slot index/candidate.
   - suspend single/group: 동일 동작 및 cancellation.
   - standalone과 실제 Cluster `MGET`은 v3 candidate만 대상으로 실행하고, v2/colon source는 per-key `GET`/`PTTL`만 실행한다.
   - refresh/register/migration/stale-cleanup Lua를 실제 Cluster에서 실행하고 `CROSSSLOT` 문자열이 없어야 한다.
   - write-script unit test에서 concurrent migration의 하나의 destination winner, index repair, source expiry race, malformed payload 경계를 고정하고, `NOSCRIPT` fallback은 기존 runner contract로 검증한다.
   - `LettuceCandidateWriteScriptTest`의 barrier test에서 source GET/PTTL 뒤 unregister가 migration을 거부하고, 이후 register가 tombstone을 지우는지 확인한다.
   - migration token과 raw value가 모두 일치할 때만 cleanup되고, 동일 payload를 다시 쓴 writer의 destination은 보존되는지 확인한다.
   - 실제 Cluster matrix는 `LettuceStrategicRedisClusterTest`의 17개 독립 test method로 추적하고, 필수 이름은 `src/test/resources/redis-cluster-test-matrix.txt` manifest에 고정한다. JUnit XML에서 `tests >= 17`, manifest 이름 누락 없음, `skipped == 0`, `failures == 0`, `errors == 0`을 확인하고, no-test/축소된 scope는 `failOnNoDiscoveredTests`와 별도 XML guard로 실패시킨다.
4. `[x]` Gradle task를 다음처럼 등록한다.

   ```kotlin
   import org.gradle.api.tasks.testing.Test

   tasks.register<Test>("clusterTest") {
       group = "verification"
       description = "Runs Redis Cluster integration tests."
       dependsOn(tasks.named("testClasses"))
       testClassesDirs = sourceSets.test.get().output.classesDirs
       classpath = sourceSets.test.get().runtimeClasspath
       failOnNoDiscoveredTests = true
       useJUnitPlatform {
           includeTags("redis-cluster")
       }
       mustRunAfter(tasks.named("test"))
       systemProperty("junit.jupiter.execution.parallel.enabled", "false")
       maxParallelForks = 1
       doLast {
           // CI와 local 모두 빈 또는 skip-only Cluster task를 성공으로 남기지 않는다.
           require(fileTree(binaryResultsDirectory).matching { include("**/*.xml") }.files.isNotEmpty()) {
               "Redis Cluster test produced no JUnit XML"
           }
       }
   }

   tasks.named<Test>("test") {
       useJUnitPlatform { excludeTags("redis-cluster") }
   }
   ```

5. `[ ]` RED/GREEN 검증 순서:
   - `./gradlew :bluetape4k-leader-redis-lettuce:test --no-daemon --no-configuration-cache --no-build-cache --console=plain`은 Cluster tag를 실행하지 않고 기존 standalone suite만 pass한다.
   - `./gradlew :bluetape4k-leader-redis-lettuce:clusterTest --no-daemon --no-configuration-cache --no-build-cache --console=plain`은 실제 container를 시작해 manifest의 모든 tagged test를 pass한다. JUnit XML은 `tests >= 17`, manifest 누락 0, `skipped = 0`, `failures = 0`, `errors = 0`이어야 하며 성공 provenance에 image digest, `cluster_state=ok`, 6개 endpoint가 있어야 한다.
   - failure/startup failure 시 image/ref/digest, `cluster_state:ok`, endpoints, `docker inspect`, logs, events를 `build/redis-cluster-diagnostics/`와 CI artifact에 남기고 환경 skip을 pass로 보고하지 않는다. report/artifact가 없으면 별도 guard가 실패한다.

## 9. Task 7 — README locale과 Nightly/manual CI gate

**Files:** `leader-redis-lettuce/README.md`, `leader-redis-lettuce/README.ko.md`, `.github/workflows/nightly-tests.yml`.

1. `[ ]` README 두 locale에 다음 사실을 동일 의미로 추가한다. English 문서는 기존 locale 계약을 유지하고 Korean 문서는 native technical prose로 작성한다.
   - standalone/Cluster constructor의 네 클래스 예제와 caller-owned connection.
   - v3 hash tag와 동일 slot, v2/colon migration precedence, mixed-version cutover.
   - v3 write 이후 old binary rollback 금지와 forward-fix 경계.
   - 현재 catalog가 resolve하는 Lettuce `7.6.0.RELEASE`에서 검증한 지원 범위, MOVED/ASK topology 및 failover 경계. 7.7 upgrade는 이 이슈 범위가 아니다.
   - `clusterTest`는 regular PR test가 아닌 Nightly/manual verification 전용이며 benchmark가 아님.
2. `[ ]` Nightly에 기존 `test-redis-lettuce`와 별도로 `test-redis-lettuce-cluster` job을 추가한다. schedule/manual full 조건, `needs: build`, `clusterTest`, `TESTCONTAINERS_RYUK_DISABLED`, `DOCKER_HOST`, test-result/diagnostic artifact를 명시한다. `coverage-report` 및 `nightly-status`의 needs에 새 job을 추가하고, full Nightly 조건에서는 cluster job 결과가 `success`가 아니면 aggregator가 실패하도록 한다. non-full/manual scope에서 의도된 `skipped`는 별도 N/A로 요약한다. `nightly-status`에는 `if: always()`와 full-scope guard를 적용해 headline green이나 unrelated job success만으로 Cluster coverage를 통과시키지 않는다.

   ```yaml
   test-redis-lettuce-cluster:
     name: Test / leader-redis-lettuce (Redis Cluster)
     if: ${{ (github.event_name == 'schedule' && github.event.schedule == '19 19 * * 0') || (github.event_name == 'workflow_dispatch' && inputs.scope == 'full') }}
     runs-on: ubuntu-latest
     timeout-minutes: 15
     needs: build
     steps:
       - uses: actions/checkout@v7
       - uses: actions/setup-java@v6.0.0
         with:
           java-version: ${{ env.JAVA_VERSION }}
           distribution: ${{ env.JAVA_DISTRIBUTION }}
       - uses: gradle/actions/setup-gradle@v6.3.0
         with:
           gradle-version: wrapper
           cache-read-only: true
       - name: Prepare Redis Cluster diagnostics
         run: mkdir -p leader-redis-lettuce/build/redis-cluster-diagnostics
       - name: Test Redis Cluster
         run: ./gradlew :bluetape4k-leader-redis-lettuce:clusterTest --no-daemon --no-configuration-cache --console=plain
         env:
           GRADLE_OPTS: "-Dorg.gradle.jvmargs=-Xmx4g -Dorg.gradle.daemon=false"
           TESTCONTAINERS_RYUK_DISABLED: "true"
           DOCKER_HOST: "unix:///var/run/docker.sock"
       - name: Verify Redis Cluster test scope
         if: always()
         shell: bash
         run: |
           set -euo pipefail
           shopt -s nullglob
           reports=(leader-redis-lettuce/build/test-results/clusterTest/*.xml)
           test "${#reports[@]}" -gt 0 || { echo "No clusterTest JUnit XML" >&2; exit 1; }
           python3 - "${reports[@]}" <<'PY'
           import sys
           from pathlib import Path
           import xml.etree.ElementTree as ET

           matrix_path = Path("leader-redis-lettuce/src/test/resources/redis-cluster-test-matrix.txt")
           expected_names = {
               line.strip() for line in matrix_path.read_text().splitlines() if line.strip()
           }
           tests = skipped = failures = errors = 0
           observed_names = set()
           for path in sys.argv[1:]:
               root = ET.parse(path).getroot()
               tests += int(root.attrib.get("tests", 0))
               skipped += int(root.attrib.get("skipped", 0))
               failures += int(root.attrib.get("failures", 0))
               errors += int(root.attrib.get("errors", 0))
               observed_names.update(
                   testcase.attrib.get("name", "").removesuffix("()")
                   for testcase in root.findall(".//testcase")
               )
           missing_names = sorted(expected_names - observed_names)
           print({"tests": tests, "skipped": skipped, "failures": failures, "errors": errors})
           print({"expected_matrix": len(expected_names), "missing_names": missing_names})
           if tests < len(expected_names) or missing_names or skipped != 0 or failures != 0 or errors != 0:
               raise SystemExit(1)

           provenance = Path("leader-redis-lettuce/build/redis-cluster-diagnostics/cluster-runtime.txt")
           if not provenance.is_file():
               raise SystemExit(1)
           fields = {
               key: value
               for line in provenance.read_text().splitlines()
               for key, separator, value in [line.partition("=")]
               if separator
           }
           endpoints = [endpoint for endpoint in fields.get("endpoints", "").split(",") if endpoint]
           if "@sha256:" not in fields.get("image_digest", "") or fields.get("cluster_state") != "ok" or len(endpoints) < 6:
               raise SystemExit(1)
           PY
       - name: Capture Redis Cluster failure diagnostics
         if: failure()
         shell: bash
         run: |
           set +e
           docker ps -a > leader-redis-lettuce/build/redis-cluster-diagnostics/docker-ps.txt
           docker ps -aq | xargs -r -n1 docker inspect > leader-redis-lettuce/build/redis-cluster-diagnostics/docker-inspect.json
           docker ps -aq | xargs -r -n1 docker logs > leader-redis-lettuce/build/redis-cluster-diagnostics/docker-logs.txt
           docker events --since 10m --until "$(date -u +%Y-%m-%dT%H:%M:%SZ)" > leader-redis-lettuce/build/redis-cluster-diagnostics/docker-events.txt
           if command -v redis-cli >/dev/null 2>&1; then redis-cli -c cluster info > leader-redis-lettuce/build/redis-cluster-diagnostics/cluster-info.txt 2>&1; fi
       - name: Upload Redis Cluster diagnostics
         if: always()
         uses: actions/upload-artifact@v7
         with:
           name: redis-lettuce-cluster-diagnostics
           path: |
             **/build/test-results/clusterTest/*.xml
             **/build/reports/tests/clusterTest/**
             **/build/redis-cluster-diagnostics/**
           retention-days: 14
   ```

3. `[ ]` `nightly-status`의 결과 검사는 다음 의미를 갖도록 갱신한다.

   ```yaml
   - name: Check all jobs
     if: always()
     env:
       RESULTS: ${{ join(needs.*.result, ',') }}
       CLUSTER_RESULT: ${{ needs.test-redis-lettuce-cluster.result }}
       FULL_SCOPE: ${{ (github.event_name == 'schedule' && github.event.schedule == '19 19 * * 0') || (github.event_name == 'workflow_dispatch' && inputs.scope == 'full') }}
     run: |
       echo "Nightly results: $RESULTS"
       if echo "$RESULTS" | grep -qE "failure|cancelled"; then
         echo "Nightly failed"
         exit 1
       fi
       if [ "$FULL_SCOPE" = "true" ] && [ "$CLUSTER_RESULT" != "success" ]; then
         echo "Redis Cluster Nightly gate is $CLUSTER_RESULT, expected success"
         exit 1
       fi
       echo "Nightly passed"
   ```

   YAML validation과 `git diff --check`를 실행한다. `.github/workflows/nightly-tests.yml` 변경 후에는 repository guard에 따라 Nightly를 명시적으로 dispatch하고, exact workflow run의 Cluster job terminal result와 artifact를 fresh read-back해야 한다. 현재 사용자는 local implementation/verification만 승인했으므로 dispatch는 별도 외부 권한 gate이며, 실행하지 않은 동안 DoD는 `PENDING`이다.

## 10. Task 8 — review artifact, lesson, full verification

**Files:** 신규 `docs/review/2026-09-04-issue-854-redis-cluster-review.md`, 신규 `docs/lessons/2026-09-04-issue-854-redis-cluster.md`, checklist.

1. `[x]` 7-Tier review를 수행한다. 각 finding에 file/line, severity, reproduction, disposition를 기록하고 P0/P1은 0이 될 때까지 수리한다. `$bluetape-kotlin-patterns`의 null-safety/immutability/structured cancellation/assertion/resource ownership 규칙을 Kotlin tier의 근거로 인용한다.

   | Tier | 검토 범위 | 필수 증거 |
   |---|---|---|
   | 1. Intent/Contract | Issue #854 범위, contention/null/error, non-goals | issue/spec/acceptance traceability |
   | 2. Architecture/API | capability adapter, four elector constructors, ABI bridge | source/consumer compile, `javap`, binary checker |
   | 3. Data/Redis | v3 hash tag, tombstone/token, Lua `KEYS`, `CROSSSLOT` | codec/slot tests, actual Cluster script output |
   | 4. Concurrency/Lifecycle | unregister↔migration ordering, TTL/source expiry, ownership | deterministic barrier and token race tests |
   | 5. Tests/CI | regular vs `clusterTest`, no-test/skip guard, fixture diagnostics, Nightly aggregation | JUnit XML counts, CI static validators, artifact paths |
   | 6. Security/Operations | brace validation, caller-owned resources, MOVED/ASK/failover and rollback boundary | negative tests, diagnostics, forward-fix runbook wording |
   | 7. Docs/ABI/Release | README parity, resolved version, consumer compatibility, delivery gates | Korean lesson/review, `dependencyInsight`, exact-head/PR-N/A proof |

2. `[x]` Korean lesson에 다음 재사용 규칙을 기록한다: 공통 hash tag, old source single-key migration, persistent lifecycle tombstone, migration token ownership, bounded TTL cleanup, same-slot destination Lua, capability adapter, actual Cluster fixture diagnostics, tag/manual gate. source/spec anchor와 실제 command/test/CI result를 함께 기록하고, 미실행 Nightly/PR/merge는 `PENDING`으로 명시한다.
3. `[x]` 검증을 heavy command 순서로 직렬 실행한다. module test 352건, manifest 기반 `clusterTest` 17건, Kover, root detekt, Gradle/custom ABI, dependencyInsight, static validators, YAML/actionlint, consumer fixture를 순차 확인했다. hosted Nightly/PR은 실행하지 않았다.

   ```bash
   ./gradlew :bluetape4k-leader-redis-lettuce:test --tests '*LettuceCandidateKeyCodecTest*' --tests '*LettuceCandidateKeyIsolationTest*' --no-daemon --no-configuration-cache --no-build-cache --console=plain
   ./gradlew :bluetape4k-leader-redis-lettuce:clusterTest --no-daemon --no-configuration-cache --no-build-cache --console=plain
   ./gradlew :bluetape4k-leader-redis-lettuce:test :bluetape4k-leader-redis-lettuce:koverXmlReport --no-daemon --no-configuration-cache --no-build-cache --console=plain
   ./gradlew detekt --no-daemon --no-configuration-cache --no-build-cache --console=plain
   ./gradlew checkBinaryCompatibility --no-daemon --no-configuration-cache --no-build-cache --console=plain
   ./gradlew :bluetape4k-leader-redis-lettuce:dependencyInsight --dependency io.lettuce:lettuce-core --configuration testRuntimeClasspath --no-daemon --no-configuration-cache --no-build-cache --console=plain
   python3 scripts/compatibility/check_binary_api_test.py
   ABI_BASE_DIR="$PWD/build/abi-base-develop" ABI_CURRENT_DIR="$PWD" python3 scripts/compatibility/check_binary_api.py
   python3 scripts/ci/validate_ci_fanout.py --static
   python3 scripts/ci/validate_kover_contract.py --static
   python3 scripts/ci/validate_test_assertion_contract.py
   python3 -c 'import pathlib, yaml; yaml.safe_load(pathlib.Path(".github/workflows/nightly-tests.yml").read_text())'
   git diff --check
   git diff --no-index --check /dev/null docs/review/2026-09-04-issue-854-redis-cluster-review.md || test $? -eq 1
   git diff --no-index --check /dev/null docs/lessons/2026-09-04-issue-854-redis-cluster.md || test $? -eq 1
   ```

   `build/abi-base-develop`는 기준 커밋 `c47324bcff5738e5505cf733d838afd730bad226`에서 수집한 정확한 baseline artifact directory로 먼저 채우고, ABI 출력의 resolved base/current version과 `unknown=0`을 확인한다. Python YAML parser가 없는 환경에서는 repository의 기존 YAML validator나 `ruby -e 'require "yaml"; YAML.load_file(".github/workflows/nightly-tests.yml")'`을 사용하고 N/A 사유를 기록한다. 각 명령은 exit 0, 기대 test count, report 생성, P0/P1 zero를 기록한다. context-mode가 Gradle 실행을 가로채면 nested `mcp__context_mode__ctx_execute` 결과를 동일한 command/evidence로 보존한다. report-only Kover가 빈 report를 exit 0으로 끝내면 coverage pass가 아니라 BLOCKED/FAIL로 기록한다. untracked review/lesson도 no-index diff-check로 whitespace 오류를 검증한다.
4. `[x]` source-to-doc·ABI·checklist 대조 후 main workflow evidence를 갱신한다. `Required checks: X/Y; N/A: N; Blocked: N`을 실제 leaf rows에서 계산하고 unchecked IDs를 숨기지 않는다.
5. `[ ]` implementation commit과 lesson/review commit은 각각 Korean Lore intent/trailers를 사용한다. PR/merge/publish는 이 계획의 완료 조건이 아니다.

## 11. Revised plan review disposition

초기 read-only plan review 세 lane의 판정은 `REQUEST CHANGES`였다. 다음 보정을 반영하고 fresh 사용자 승인 뒤 구현·로컬 검증을 완료했다. 호스팅 Nightly와 PR은 별도 외부 게이트이므로 여기서는 실행하지 않았다.

| finding | revised 대응 | 상태 |
|---|---|---|
| catalog/API version mismatch | 현재 immutable catalog의 Lettuce `7.6.0.RELEASE`로 고정, 7.7 upgrade 제거, `dependencyInsight` 증거 추가 | 검증 완료 |
| standalone Kotlin ABI bridge | `@JvmOverloads` secondary constructor, baseline/after `javap`, precompiled Kotlin consumer, `RedisScriptRunner` 별도 descriptor 검사 | 검증 완료 |
| unregister→migration resurrection | same-slot persistent tombstone/fence, source 숨김, deterministic barrier test | 검증 완료 |
| same-payload destructive cleanup | migration unique token + raw value compare, candidate/index/token만 cleanup, source key는 script에서 제외 | 검증 완료 |
| clusterTest/CI false green | `testClassesDirs`/`classpath`/`dependsOn`/`failOnNoDiscoveredTests`, regular tag exclusion, JUnit XML count/skip guard, full-scope Nightly aggregator와 diagnostics artifact | 로컬 검증 완료; hosted Nightly PENDING |
| stale legacy index / source precedence | v2·colon key를 모두 preflight하고 stale member가 속한 index만 제거하며, 하위 우선순위 malformed source도 표면화 | 검증 완료; blocking/suspend isolation 회귀 |
| Cluster matrix scope | public blocking/suspend single/group lifecycle·migration·TTL·cancellation을 17개 독립 test와 checked-in manifest로 고정하고 Gradle/CI가 필수 이름을 exact 검증 | 로컬 검증 완료; hosted Nightly PENDING |
| mutable fixture image tag | image tag와 실제 image digest, helper, `cluster_state`, 6개 endpoint, Docker host provenance를 성공 artifact에 기록하고 tag pinning은 upstream fixture 후속 범위로 명시 | provenance 로컬 검증 완료; immutable pull P2 후속 |
| rollback claim overreach | 별도 recovery tool/parity 변환을 비범위로 명시하고 v3 write 뒤 stop·보존·forward fix만 허용 | 검증 완료 |

초기 구현 전에는 이 표의 P0/P1 항목과 `A-04` 승인 상태가 수렴되기 전 source code mutation, Nightly dispatch, PR 생성 또는 merge를 실행하지 않는 stop condition을 적용했다. 현재 구현은 로컬 증거를 갱신했지만, mutable fixture pinning·hosted Nightly·PR은 별도 PENDING 범위로 남긴다.

구현 후 확인 결과: catalog는 `7.6.0.RELEASE`로 고정되었고 네 elector의 standalone descriptor·Kotlin synthetic bridge·Cluster constructor가 보존/추가되었다. v3 same-slot write와 tombstone/token fence, standalone·Cluster native `MGET`, v2/colon single-key migration, persistent-source TTL 경계를 코드와 테스트로 검증했다. TTL 입력 사전 검증과 malformed/mismatched destination 처리도 blocking/suspend 및 Cluster 회귀 테스트로 고정했다. stale legacy index와 하위 우선순위 malformed source 회귀를 추가해 lookup precedence를 고정했고, `test` 352건과 manifest 기반 `clusterTest` 17건은 모두 성공했다. Cluster 성공 provenance에는 `tommy351/redis-cluster:6.2`의 실제 image digest, `cluster_state=ok`, 6개 mapped endpoint와 fixture/helper가 기록되며 Gradle/Nightly가 manifest 이름 누락도 거부한다. root `detekt`, Gradle/custom ABI, dependencyInsight, static validators, YAML/actionlint, Kotlin/Java consumer fixture도 통과했다. hosted Nightly dispatch와 PR/CI는 권한 경계 때문에 `PENDING`이다.

## 12. Stop, rollback, and handoff conditions

- P0/P1 finding, actual `CROSSSLOT`, destination TTL이 observed source TTL보다 길어지는 증거, malformed payload의 silent fallback, cancellation swallow, public descriptor 삭제, fixture diagnostics 누락이 있으면 다음 task로 진행하지 않고 해당 task를 RED 상태로 되돌린다.
- Cluster image pull/daemon/topology 환경이 준비되지 않으면 test를 skip하지 않는다. failure diagnostics를 남기고 `Blocked: 1`로 보고하며 환경 복구 후 동일 SHA에서 재실행한다.
- v3 write가 한 번이라도 관찰된 뒤에는 old binary rollback을 수행하지 않는다. 별도 parity/recovery tool은 이 이슈에 포함하지 않으며, emergency stop → 상태 보존·진단 → forward fix 순서만 허용한다.
- 구현·검증 완료 후에도 Nightly dispatch, push, PR, merge, tag/release, branch/worktree cleanup은 각각 fresh exact-head 승인 gate를 기다린다.
- 최종 handoff는 변경 파일 목록, exact HEAD, test/static/ABI/Cluster evidence, review/lesson 경로, `Required checks` count, 남은 N/A/Blocked 및 사용자의 다음 승인 지점을 포함한다.
