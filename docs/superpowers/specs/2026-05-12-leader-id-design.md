# Design Spec — `leaderId` audit identity for `LeaderElector` / `LeaderGroupElector` API

- **Issue**: #72 — feat: `@LeaderGroupElection` leaderId 지원 (LeaderGroupElector API 변경)
- **Date**: 2026-05-12
- **Branch**: `feat/leader-group-leaderid-72`
- **Worktree**: `.worktrees/feat-leader-group-leaderid`
- **Spec author**: Codex + feature-dev:code-architect (Opus)

---

## 1. 배경 (Problem)

`@LeaderGroupElection` AOP 어노테이션 (#41) 설계 시 `leaderId: String` 필드가 도입 후보였으나, Codex 리뷰에서 다음 지적:

> `LeaderGroupElection.runIfLeader(lockName, action)` API 는 leaderId 를 받는 자리가 없고, Redisson group 구현은 semaphore permit 만 획득. leaderId 가 어디에 전달되는지, 상태/소유권/해제 검증에 쓰이는지 정의 없음.

#41 본 PR 에서는 leaderId 필드를 제거했고, 본 이슈로 후속 작업. 본 spec 은 leaderId 의 **의미(semantic)**, **API 형태**, **백엔드 영향 범위** 를 확정하고, 8개 PR phase 로 분할 구현하는 청사진을 제공한다.

### 제약 / 미지(constraints)

- 7+ backend 모두 영향 (Local, Lettuce, Redisson, Mongo, Hazelcast, ZooKeeper, Exposed-JDBC, Exposed-R2DBC)
- 4개 execution model 모두 (sync / suspend / async / VT)
- AOP annotation 2개 (group + single) + Spring Boot autoconfig 1개
- 기존 `LeaderLease.leaderId` 의미(node identity) 와 신규 audit identity 충돌 가능

---

## 2. Design Risks / Failure Modes (5)

| # | Risk | 대응 |
|---|------|------|
| R1 | `LeaderLease.leaderId` 의미 변경(node id → election audit id) → 기존 consumer 가 `host:pid` 기대 시 silent drift | Phase A0 grep gate + 명시적 `LeaderLease.nodeId` 추가 + CHANGELOG entry |
| R2 | Kotlin `runIfLeader(lockName, leaderId = "", action)` overload 시 JVM `$default` stub signature 변경 → Java 호출자 깨질 위험 | overload 회피, `LeaderSlot` value object 도입 |
| R3 | Redisson `RPermitExpirableSemaphore` 가 permitId 발급 → caller 정의 token 으로 치환 불가 | Audit-only semantic 채택 (caller leaderId 와 ownership token 병행) |
| R4 | SpEL 평가 실패 (annotation runtime exception) → 락 획득 전에 예외 → service 영향 | startup pre-parse 강제 (`LeaderAnnotationValidatorBeanPostProcessor`), runtime SpEL eval 실패는 기존 `failureMode` 정책 따름 |
| R5 | `Hostname` 기반 leaderId 가 multi-tenant SaaS 환경 audit log 에 PII 누출 위험 | 기본 provider = `RandomLeaderIdProvider` (Base58), Hostname 은 명시적 opt-in |

---

## 3. 접근 비교 (3 approaches)

### Approach 1: Audit-only (CHOSEN)

- Backend ownership token (Redisson permitId / Lettuce Base58 / Mongo per-slot token) 그대로 유지
- `leaderId` 는 `LeaderLease.leaderId`, `LeaderRunResult.Elected.leaderId`, `LeaderElectionInfo.leaderId`, Micrometer metric tag, `LeaderLockHandle.Real.auditLeaderId` 등 **audit/관측** 표면에만 노출
- Caller 가 정의한 `leaderId` 는 락 소유권 검증에 사용되지 **않음** — 별도 metadata column/field 에 병행 저장

**Pros**: 4개 Redis 백엔드 변경 최소, Redisson 호환, 기존 ownership token 안정성 유지, multi-tenant audit 충분.
**Cons**: leaderId 가 release 검증 키가 아니므로 caller 가 "내가 holder 인지" 직접 확인 불가 (간접 — `LeaderLockHandle.auditLeaderId` 와 equal 비교).

### Approach 2: nodeId override per-call

- 기존 `LeaderGroupElectionOptions.nodeId` plumbing 재활용
- `runIfLeader` 호출 시 `nodeId` 일시 override

**Pros**: 신규 객체/필드 최소.
**Cons**: nodeId 의미 흐려짐 ("machine identity" vs "per-call audit id"), `LeaderLease.leaderId` 가 `nodeId` 와 어차피 동기화 — Approach 1 과 의미상 동일하지만 표면 더 혼란스러움. 명명·문서화 비용 큼.

**Rejected**: 의미 분리 명확성 부족.

### Approach 3: Replace ownership token

- Caller-supplied `leaderId` 가 release/extend 검증 키가 됨
- Local/Lettuce/Mongo 는 string equality 만으로 가능, **Redisson 은 `RPermitExpirableSemaphore` 포기 필요** → custom Lua semaphore 로 전면 재설계

**Pros**: leaderId 가 1급 시민, caller 직접 ownership 검증 가능.
**Cons**: Redisson 백엔드 fork 수준 redesign, 4개 Redis backend 의 일관성 깨짐, slot-token TTL 모델 전체 재검증. Risk R3 가 blocker.

**Rejected**: Redisson 백엔드 redesign 비용 > 직접 ownership 검증 가치.

---

## 4. 채택 결정 (Approved Decisions)

User 합의 (2026-05-12):

| Decision | Value |
|----------|-------|
| Semantic | **A) Audit-only** |
| Backend scope | **Full 7+** — Local + Lettuce + Redisson + Mongo + Hazelcast + ZooKeeper + Exposed-JDBC + Exposed-R2DBC |
| API shape | **`LeaderSlot(lockName, leaderId)` value object** (data class, not inline) |
| Annotation symmetry | **Yes — `@LeaderGroupElection` + `@LeaderElection` 둘 다** |
| Result-API parity | **All 4 execution models** — sync + suspend + async + VT 모두 `runIfLeaderResult*` variant |

---

## 5. 공통 패턴 준수 (Patterns to follow)

| Pattern | Citation | Conformance |
|---------|----------|-------------|
| INHERIT sentinel (annotation → property fallback) | `LeaderAspectFailureMode.INHERIT` in `LeaderGroupElectionAspect.kt:543` | `leaderId: String = ""` 빈 문자열을 sentinel 로 사용 |
| Slot-token TTL model | `AbstractLocalLeaderGroupElector.kt:125` | Ownership token (Base58/permitId/Mongo token) 자동 생성 유지; `leaderId` 는 audit 병행 |
| `LockIdentity` shape | `leader-core/.../LockIdentity.kt` | `leaderId` 는 `LockIdentity` 에 **포함하지 않음** — 동일 lock 의 reentrancy 보존 |
| testFixtures contract test 패턴 | `Abstract{Group,}LockExtenderContractTest.kt` | 신규 `AbstractLeaderIdContractTest` × {sync/suspend/async/vt} × {single/group} = 최대 8 abstract |
| `runCatching {}` ban inside suspend | `LeaderGroupElectionAspect.kt:172-173` | 신규 suspend 분기도 manual try-catch + `CancellationException` 우선 rethrow |
| `KLogging` companion | workspace CLAUDE.md | `LeaderIdProvider` 구현체는 `KLogging`; hot path `nextLeaderId()` 에는 log 금지 |
| `requireXxx()` validation | `LeaderLease.kt:35` | `LeaderSlot.init { lockName.requireNotBlank("lockName"); leaderId.requireNotBlank("leaderId") }` |
| Same-typed-pair → data class wrapper | workspace CLAUDE.md "함수 인자" 규칙 | `LeaderSlot(lockName, leaderId)` = String × 2 → data class 명시 적용 |
| Caffeine-cached SpEL evaluator | `SpelExpressionEvaluator.kt` | 신규 `leaderId` SpEL 도 동일 evaluator 통과 |
| Interface default methods (`-jvm-default=enable`) | `SuspendLeaderElector.kt:55-58` | 신규 메서드는 interface default 로 추가 → binary-compat 보존 |
| `LeaderAnnotationValidatorBeanPostProcessor` startup pre-parse | annotation `name` 필드 검증 경로 | `leaderId` 도 startup 시 SpEL pre-parse |

---

## 6. 핵심 결정 (D1–D8)

### D1. `LeaderSlot` value object — 위치 / 형태

```kotlin
// leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderSlot.kt
data class LeaderSlot(val lockName: String, val leaderId: String) : Serializable {
    init {
        lockName.requireNotBlank("lockName")
        leaderId.requireNotBlank("leaderId")
    }
    companion object {
        private const val serialVersionUID: Long = 1L
        fun of(lockName: String, provider: LeaderIdProvider): LeaderSlot =
            LeaderSlot(lockName, provider.nextLeaderId(lockName))
    }
}
```

- **`data class`** (not Kotlin `value class`): 백엔드/aspect/metric 에서 boxing 빈도 높음, inline boxing cost > 1 allocation
- `Serializable` for parity with `LeaderLease` / `LeaderElectionOptions`
- 같은 패키지 (`io.bluetape4k.leader`) — `LeaderLease.kt` 옆 위치

### D2. `LeaderIdProvider` 인터페이스 / 구현

```kotlin
// leader-core/src/main/kotlin/io/bluetape4k/leader/identity/LeaderIdProvider.kt
fun interface LeaderIdProvider {
    fun nextLeaderId(lockName: String): String
}

// RandomLeaderIdProvider — DEFAULT
class RandomLeaderIdProvider(private val length: Int = DefaultLength) : LeaderIdProvider {
    override fun nextLeaderId(lockName: String): String = Base58.randomString(length)
    companion object {
        const val DefaultLength: Int = 12
        @JvmField val Default: LeaderIdProvider = RandomLeaderIdProvider()
    }
}

// HostnamePidLeaderIdProvider — opt-in (PII warning)
class HostnamePidLeaderIdProvider(private val suffixLength: Int = DefaultSuffixLength) : LeaderIdProvider {
    override fun nextLeaderId(lockName: String): String =
        "${LeaderNodeId.Default}:${Base58.randomString(suffixLength)}"
    companion object { const val DefaultSuffixLength: Int = 6 }
}

// CompositeLeaderIdProvider — prefix wrapping (tenant tag)
class CompositeLeaderIdProvider(
    private val prefix: String,
    private val delegate: LeaderIdProvider = RandomLeaderIdProvider.Default,
    private val separator: String = "-",
) : LeaderIdProvider {
    init { prefix.requireNotBlank("prefix") }
    override fun nextLeaderId(lockName: String): String =
        "$prefix$separator${delegate.nextLeaderId(lockName)}"
}
```

- Default bound bean = `RandomLeaderIdProvider.Default` (PII conservative)
- `HostnamePidLeaderIdProvider` KDoc: "Emits hostname:pid in audit logs; do NOT use in multi-tenant SaaS where hostname is sensitive."

### D3. `nodeId` vs `leaderId` — orthogonal

- `LeaderElectionOptions.nodeId` / `LeaderGroupElectionOptions.nodeId` = **node-level JVM identity** (default `host:pid`). **변경 없음.**
- `LeaderSlot.leaderId` = **per-election audit token** (default random Base58). 신규.

**`LeaderLease` schema 변경**:
- 기존: `LeaderLease.leaderId` 에 `nodeId` 가 저장됨 (`LocalLeaderStateRegistry.kt:111-119`).
- 변경 후: `LeaderLease.leaderId` 는 **per-election audit id** 저장; `nodeId` 는 새 필드 `LeaderLease.nodeId: String? = null` 로 분리.

```kotlin
data class LeaderLease(
    val leaderId: String,
    val electedAt: Instant? = null,
    val leaseUntil: Instant? = null,
    val slot: Int? = null,
    val nodeId: String? = null,          // NEW — additive, positional last
) : Serializable
```

- 신규 `nodeId` 필드는 마지막 positional → 기존 positional constructor caller 깨지지 않음 (default 적용).
- **CHANGELOG** 에 `LeaderLease.leaderId` 의미 변경 명시 + migration: 기존 `lease.leaderId` (host:pid 기대) 읽는 코드는 `lease.nodeId` 로 변경 필요.
- Phase B0 grep gate: `grep -r "lease.leaderId" leader-*/src` 모든 consumer 확인.

### D4. `LeaderRunResult.Elected` — `leaderId` 추가

```kotlin
data class Elected<out T>(val value: T?, val leaderId: String = "") : LeaderRunResult<T>
```

- Source-compat: `Elected(value)` (1-arg positional) 보존.
- `componentN` destructuring: 기존 `val (v) = elected` 동작; 신규 `val (v, id) = elected` 추가 가능.
- `equals/hashCode` 가 `leaderId` 포함 → 기존 `Elected(x) == Elected(x)` 비교는 둘 다 default `""` 이므로 passing 유지.

### D5. `LeaderElectionInfo` — `leaderId` 추가 (additive)

```kotlin
data class LeaderElectionInfo(
    val lockName: String,
    val wasElected: Boolean,
    val leaderId: String = "",        // NEW — additive, third positional with default
) : AbstractCoroutineContextElement(Key), CoroutineContext.Element
```

- Coroutine context consumer: `coroutineContext[LeaderElectionInfo]?.leaderId`.

### D6. 기존 `runIfLeader(lockName, action)` — Non-deprecated wrapper

```kotlin
override fun <T> runIfLeader(lockName: String, action: () -> T): T? =
    runIfLeader(LeaderSlot(lockName, idProvider.nextLeaderId(lockName)), action)
```

- **No `@Deprecated`**: 기존 사용자에 unwarranted warning 부여 안 함.
- Audit 안 쓰는 사용자는 lockName-only 메서드 그대로 사용 가능.
- Primary 메서드는 `LeaderSlot` 오버로드, lockName overload 는 thin wrapper.

### D7. Annotation `leaderId` SpEL — fallback chain

```kotlin
private fun resolveLeaderId(
    meta: GroupAdviceMetadata,
    method: Method,
    args: Array<Any?>,
    target: Any,
    lockName: String,
): String {
    // 1. annotation literal (LITERAL_PATTERN fast-path)
    if (meta.leaderIdLiteral != null && meta.leaderIdLiteral.isNotEmpty()) return meta.leaderIdLiteral
    // 2. annotation SpEL (preParsed at startup)
    if (meta.leaderIdExpression.isNotBlank()) return spel.evaluate(meta.leaderIdExpression, method, args, target)
    // 3. property fallback
    if (props.defaultLeaderId.isNotBlank()) return props.defaultLeaderId
    // 4. LeaderIdProvider bean
    return idProvider.nextLeaderId(lockName)
}
```

- 1단계: annotation `leaderId = "static-string"` → literal fast-path
- 2단계: annotation `leaderId = "'tenant-' + #tenantId"` → SpEL eval
- 3단계: property `bluetape4k.leader.aop.default-leader-id` → static fallback
- 4단계: `LeaderIdProvider` bean (default `RandomLeaderIdProvider`)

### D8. 8개 PR 로 phase 분할

| PR | Phases | 목표 |
|----|--------|------|
| PR1 | A+B+C | 코어 타입 + 인터페이스 + Local 백엔드 + testFixtures contract |
| PR2 | D | Lettuce + Redisson |
| PR3 | E | Mongo |
| PR4 | F1 | Hazelcast |
| PR5 | F2 | ZooKeeper |
| PR6 | G | Exposed-JDBC + Exposed-R2DBC |
| PR7 | H+I | AOP + Spring auto-config |
| PR8 | J+K | Cross-backend integration tests + README + CHANGELOG |

각 backend PR 은 PR1 의 `AbstractLeaderIdContractTest` 를 subclass — additive, rebase 충돌 없음.

---

## 7. Data Flow (Lettuce-group 예시)

```
Caller code:
  election.runIfLeader(LeaderSlot("report-job", "leader-A"))
        │
        ▼ (또는 @LeaderGroupElection AOP 경로)
┌─────────────────────────────────────────────────────────┐
│ LeaderGroupElectionAspect.aroundLeader                  │
│   1) resolveLockName(meta, ...)  → "report-job"         │
│   2) resolveLeaderId(meta, ...)                         │
│       - annotation literal: "leader-A"                  │
│       - 또는 SpEL eval                                  │
│       - 또는 property fallback                          │
│       - 또는 idProvider.nextLeaderId("report-job")      │
│   3) slot = LeaderSlot("report-job", resolvedLeaderId)  │
│   4) elector.runIfLeaderResult(slot) { ... }            │
└────────────────────────┬────────────────────────────────┘
                         ▼ (LeaderSlot 전달)
┌─────────────────────────────────────────────────────────┐
│ LettuceLeaderGroupElector.runIfLeader(slot, action)     │
│   - LettuceSlotTokenGroup.acquire(                      │
│       slot.lockName, waitTime, leaseTime,               │
│       audit=slot.leaderId)                              │
│       ── Lua atomically:                                │
│         · ZADD lg:report-job <expireAt> <Base58 token>  │
│         · HSET lg:report-job:meta <token> "leader-A"    │
│       ── returns (token="X9aB", slot=1)                 │
│   - Build LeaderLease(                                  │
│       leaderId="leader-A",     ← audit                  │
│       nodeId=host:pid,         ← node identity          │
│       slot=1, electedAt, leaseUntil)                    │
│   - Build LeaderLockHandle.Real(                        │
│       identity=LockIdentity("report-job", GROUP, ...),  │
│       token="X9aB",            ← ownership              │
│       auditLeaderId="leader-A", ← audit                 │
│       slotId="1", extendDelegate=...)                   │
│   - listeners.notifyElected("report-job")               │
│   - metric.onLockAcquired(slot.leaderId, ...)           │
└────────────────────────┬────────────────────────────────┘
                         ▼
       action() 실행 — LockAssert.peekHandle().auditLeaderId 가시
                         │
                         ▼ (try/finally — 항상 release)
┌─────────────────────────────────────────────────────────┐
│ Release:                                                │
│   - LettuceSlotTokenGroup.release(token="X9aB", ...)    │
│     ── Lua atomically:                                  │
│       · ZREM lg:report-job X9aB                         │
│       · HDEL lg:report-job:meta X9aB                    │
│   - metric.onTaskFinished("report-job", elapsedNs)      │
└────────────────────────┬────────────────────────────────┘
                         ▼
Aspect → LeaderRunResult.Elected(value, "leader-A")
```

**Invariants**:
- Base58 ownership token **"X9aB"** = backend 가 발급, release 검증 키
- **"leader-A"** = caller audit identity, **병행** 저장 (replace 아님)
- 둘 다 `LeaderLockHandle.Real` 에 존재하지만 `equals/hashCode` 는 `token` 만 사용

---

## 8. 백엔드별 변경 매트릭스

| Backend | Group elector | Single elector | Suspend group | Suspend single | Side effect |
|---------|---------------|----------------|---------------|----------------|-------------|
| Local | `local/LocalLeaderGroupElector.kt` | `local/LocalLeaderElector.kt` | `coroutines/LocalSuspendLeaderGroupElector.kt` | `coroutines/LocalSuspendLeaderElector.kt` | `LocalLeaderStateRegistry.acquireGroup(lockName, leaderId, nodeId, ...)` signature 분리 |
| Lettuce | `leader-redis-lettuce/.../LettuceLeaderGroupElector.kt` | `LettuceLeaderElector.kt` | `coroutines/LettuceSuspendLeaderGroupElector.kt` | `coroutines/LettuceSuspendLeaderElector.kt` | `semaphore/LettuceSlotTokenGroup.kt` Lua: ZSET 옆에 `lg:<name>:meta` Hash 로 token→leaderId |
| Redisson | `leader-redis-redisson/.../RedissonLeaderGroupElector.kt` | `RedissonLeaderElector.kt` | `coroutines/RedissonSuspendLeaderGroupElector.kt` | `coroutines/RedissonSuspendLeaderElector.kt` | `RMap<permitId,leaderId>` 동행 저장 |
| Mongo | `leader-mongodb/.../MongoLeaderGroupElector.kt` | `MongoLeaderElector.kt` | `coroutines/MongoSuspendLeaderGroupElector.kt` | `coroutines/MongoSuspendLeaderElector.kt` | `lock/MongoLock.kt`: slot doc 에 `leaderId: String?` 필드 추가 (TTL index 영향 없음) |
| Hazelcast | `leader-hazelcast/.../HazelcastLeaderGroupElector.kt` | `HazelcastLeaderElector.kt` | (verify) | (verify) | IMap value 에 `leaderId` 추가 |
| ZooKeeper | `leader-zookeeper/.../ZookeeperLeaderGroupElector.kt` | `ZookeeperLeaderElector.kt` | (verify) | (verify) | znode payload JSON 에 `leaderId` 추가 |
| Exposed-JDBC | `leader-exposed-jdbc/.../ExposedLeaderGroupElector.kt` | `ExposedLeaderElector.kt` | n/a | n/a | `LeaderGroupLockTable` 에 `audit_leader_id VARCHAR(256) NULL` 컬럼 추가 (additive) |
| Exposed-R2DBC | mirror | mirror | suspend native | suspend native | 동일 컬럼 |

각 backend 변경 패턴 (uniform):

1. `idProvider: LeaderIdProvider = RandomLeaderIdProvider.Default` 생성자 주입
2. `runIfLeader(slot: LeaderSlot, action)` primary override
3. `runIfLeader(lockName, action)` legacy → primary delegate
4. `slot.leaderId` 를 다음에 stamp:
   - `LeaderLease.leaderId` (state 저장)
   - `LeaderLockHandle.Real.auditLeaderId`
   - metric `onLockAcquired(slot.leaderId, ...)`
5. 백엔드별 메타 저장 (Hash / Map / 컬럼 / znode JSON)

---

## 9. AOP 변경 (Phase H)

### Annotation

```kotlin
// leader-core/src/main/kotlin/io/bluetape4k/leader/annotation/LeaderGroupElection.kt
annotation class LeaderGroupElection(
    val name: String,
    val maxLeaders: Int = -1,
    val waitTime: String = "",
    val leaseTime: String = "",
    val minLeaseTime: String = "PT0S",
    val bean: String = "",
    val failureMode: LeaderAspectFailureMode = LeaderAspectFailureMode.INHERIT,
    val leaderId: String = "",                  // NEW — SpEL 또는 literal
)

// 동일하게 @LeaderElection 에도 leaderId 추가
```

### Aspect

- `LeaderGroupElectionAspect`, `LeaderElectionAspect` 모두 `idProvider: LeaderIdProvider` 생성자 주입
- `GroupAdviceMetadata` 에 `leaderIdExpression: String`, `leaderIdLiteral: String?` 필드 추가
- `resolveMetadata(method, ann)` 에서 pre-parse 결과 캐시
- `resolveLeaderId(meta, method, args, target, lockName)` helper (D7 참조)
- sync / suspend / Mono 3개 분기 모두 `LeaderSlot(resolvedName, resolvedLeaderId)` 구성 후 신규 primary 호출
- `LeaderElectionInfo` 생성 시 `leaderId = resolvedLeaderId` 전달

### Validator

`LeaderAnnotationValidatorBeanPostProcessor`:
- annotation `leaderId` 가 비어있지 않고 `LITERAL_PATTERN` 미일치 → `spel.preParse(ann.leaderId)` 호출
- 실패 → `IllegalStateException` (parity with `name` 필드 — strict 모드 무관)

### Properties

```kotlin
// LeaderAopProperties.kt
val defaultLeaderId: String = ""
// YAML key: bluetape4k.leader.aop.default-leader-id
```

`LeaderGroupProperties` / `LeaderElectionProperties` 는 변경 없음 (audit identity 는 AOP 레벨).

### Auto-config

```kotlin
// LeaderAopFactoryAutoConfiguration.kt
@Bean
@ConditionalOnMissingBean(LeaderIdProvider::class)
fun leaderIdProvider(): LeaderIdProvider = RandomLeaderIdProvider.Default
```

---

## 10. 테스트 전략 (Phase J)

### testFixtures contract

```kotlin
// leader-core/src/testFixtures/.../contract/AbstractLeaderIdContractTest.kt
abstract class AbstractLeaderIdContractTest {
    abstract fun createGroupElector(maxLeaders: Int): LeaderGroupElector
    abstract fun createSingleElector(): LeaderElector

    @Test fun `LeaderRunResult Elected carries leaderId from LeaderSlot`() { ... }
    @Test fun `default provider yields unique leaderId per call`() { ... }
    @Test fun `LeaderLease leaderId equals LeaderSlot leaderId after acquire`() { ... }
    @Test fun `nodeId is preserved on LeaderLease alongside leaderId`() { ... }
    @Test fun `concurrent runIfLeader calls each have distinct leaderId`() {
        // MultithreadingTester (workers × rounds)
    }
}

abstract class AbstractSuspendLeaderIdContractTest { /* SuspendedJobTester */ }
abstract class AbstractAsyncLeaderIdContractTest { /* future-based */ }
abstract class AbstractVirtualThreadLeaderIdContractTest { /* StructuredTaskScopeTester */ }
```

- 4개 abstract × {single, group} = 최대 8 클래스
- 각 backend 모듈에서 subclass — `LettuceGroupLeaderIdTest extends AbstractLeaderIdContractTest`

### AOP integration test

```kotlin
// LeaderIdAopIntegrationTest.kt — ApplicationContextRunner
@Test fun `annotation literal leaderId flows through SpEL fast-path`()
@Test fun `annotation SpEL expression evaluates with method args`()
@Test fun `empty annotation + property default-leader-id flows as fallback`()
@Test fun `all empty + default provider yields random`()
@Test fun `user-provided LeaderIdProvider bean overrides default`()
@Test fun `invalid SpEL leaderId fails context startup`()
```

### Metrics

```kotlin
// LeaderIdMetricsTagsTest.kt — leader-micrometer 의존
@Test fun `Micrometer counter carries leader.id tag from slot.leaderId`()
```

### Concurrency tester 활용 (mandatory per memory rule)

- `MultithreadingTester`: sync, async, VT 분기
- `SuspendedJobTester`: suspend 분기
- `StructuredTaskScopeTester`: VT structured concurrency 검증

직접 `Thread` / `Executors.newFixedThreadPool` / `coroutineScope { launch }` 작성 금지.

---

## 11. README / KDoc / CHANGELOG 영향

| 파일 | 변경 |
|------|------|
| `leader-core/README.md` + `README.ko.md` | "Audit Identity" 섹션 + Mermaid sequence diagram |
| `leader-spring-boot/README.md` + `README.ko.md` | annotation `leaderId` 사용 예시 (literal + SpEL) |
| `leader-redis-{lettuce,redisson}/README.md` + `README.ko.md` | audit identity 저장 방식 noting |
| `leader-mongodb/README.md` + `README.ko.md` | slot doc schema 변경 noting |
| `leader-{hazelcast,zookeeper}/README.md` + `README.ko.md` | metadata location noting |
| `leader-exposed-{jdbc,r2dbc}/README.md` + `README.ko.md` | `audit_leader_id` 컬럼 ALTER 안내 |
| Root `README.md` + `README.ko.md` | feature highlight + cross-link |
| `CHANGELOG.md` | **`LeaderLease.leaderId` semantic change** + additive `nodeId` field + `Elected` destructuring 변경 + Exposed 스키마 추가 |

- 모든 public 변경 KDoc은 **English**
- README locale set 동기화 (다국어 정책)

---

## 12. 빌드 시퀀스 (TDD 순서)

각 단계는 다음을 gate:

- [ ] **Step 0** (Phase A0 grep gate): `grep -r "lease.leaderId" leader-*/src` 모든 consumer 확인. host:pid 기대 reader 가 있으면 `lease.nodeId` 로 patch 후 진행
- [ ] **Step 1** (RED Phase A): `LeaderSlot` unit + `RandomLeaderIdProvider` uniqueness test (`MultithreadingTester`)
- [ ] **Step 2** (GREEN Phase A): 신규 파일 생성. 테스트 통과
- [ ] **Step 3** (RED Phase B): `LeaderSlot` overload mock test on 8 interface
- [ ] **Step 4** (GREEN Phase B): interface default 메서드 추가 (binary-compat)
- [ ] **Step 5** (RED Phase C): `AbstractLeaderIdContractTest` subclass — `Local{Sync,Suspend,Async,VT}{Single,Group}Elector` 8 test classes
- [ ] **Step 6** (GREEN Phase C): `LocalLeaderStateRegistry` 시그니처 + 8 Local impl 수정. `ide_diagnostics` clean
- [ ] **Step 7**: `./gradlew :leader-core:build` clean → **PR1 merge**
- [ ] **Step 8** (Phase D Lettuce): RED `LettuceGroupLeaderIdTest` → GREEN Lua + elector
- [ ] **Step 9** (Phase D Redisson): RED → GREEN
- [ ] **Step 10**: `./gradlew :leader-redis-lettuce:test :leader-redis-redisson:test` clean → **PR2 merge**
- [ ] **Step 11** (Phase E Mongo): RED → GREEN → **PR3 merge**
- [ ] **Step 12** (Phase F1 Hazelcast): RED → GREEN → **PR4 merge**
- [ ] **Step 13** (Phase F2 ZooKeeper): RED → GREEN → **PR5 merge**
- [ ] **Step 14** (Phase G Exposed): RED → GREEN + DB migration test (Testcontainers Postgres) → **PR6 merge**
- [ ] **Step 15** (Phase H RED): `LeaderIdAopIntegrationTest` — annotation literal / SpEL / property fallback / provider fallback / validator failure
- [ ] **Step 16** (Phase H+I GREEN): aspect + properties + validator + auto-config
- [ ] **Step 17** (Phase J): cross-backend matrix test (nightly profile)
- [ ] **Step 18**: `./gradlew detekt` clean across all touched modules
- [ ] **Step 19**: Kover ≥ 80% on `leader-core`, ≥ 60% on backend modules
- [ ] **Step 20** (Phase K): README + KDoc + CHANGELOG → **PR7 + PR8 merge**

---

## 13. Critical Details

### Error handling
- **Invalid SpEL** `leaderId`: startup 시 pre-parse 실패 → `IllegalStateException` (`name` 필드와 parity, strict 모드 무관)
- **Runtime SpEL eval failure**: `LeaderGroupElectionException` propagation → 기존 `failureMode` 정책 (RETHROW / SKIP / FAIL_OPEN_RUN)
- **Empty leaderId**: `LeaderIdProvider.Default` 가 항상 non-empty 보장 (Base58(12)); `LeaderSlot.init` 가 last-line of defense

### Threading
- `SpelExpressionEvaluator` 이미 Caffeine + thread-safe
- `RandomLeaderIdProvider` 내부 `Base58.randomString` → `ThreadLocalRandom` 기반, lock-free
- `LeaderLockHandle.withReentryDepth(n)` copy 생성자에 `auditLeaderId` 보존 필요 (`LeaderLockHandle.kt:91` 수정)

### Performance
- `LeaderSlot` 1 allocation/call: 기존 `LeaderLease` + `LeaderLockHandle.Real` + Base58 token 대비 negligible
- Literal `leaderId` SpEL short-circuit (`LITERAL_PATTERN`) — 기존 `name` 필드와 동일 fast-path
- SpEL eval 결과는 Caffeine cache per-method

### Security / PII
- **Default provider** = `RandomLeaderIdProvider` (no host/pid)
- `HostnamePidLeaderIdProvider` KDoc 명시 경고
- SpEL `leaderId` 평가는 `name` 과 동일 envelope: `bluetape4k.leader.aop.spel.allow-method-invocation=false` 기본

### Migration
- `LeaderLease.leaderId` 의미 변경 (R1 / D3 관련) — CHANGELOG 의무 + `nodeId` 추가 + Phase A0 grep gate
- `LeaderRunResult.Elected` source-compat 유지 (positional 1-arg 그대로)
- `runIfLeader(lockName, action)` non-deprecated, wrapper 만 됨
- Exposed `audit_leader_id` 컬럼 추가는 additive — 기존 row 영향 없음

---

## 14. Open Questions / Trade-offs (committed but flagged)

| # | 결정 | 대안 | 이유 |
|---|------|------|------|
| O1 | `LeaderLease.leaderId` 의미 변경 + `LeaderLease.nodeId` 추가 | `LeaderLease.auditLeaderId` 신규 + `leaderId == nodeId` 유지 | 첫째: API 일관성 (`Elected.leaderId`, `Info.leaderId` 와 같은 의미). 둘째: 세 번째 필드 confusing overlap 회피 |
| O2 | `LeaderLockHandle.Real.auditLeaderId` 필드 추가 | `Lease` 만 갖고 `LockStateHolder` 통해 우회 | 직접 access 더 자연스럽고 metric/LockAssert 단순화 |
| O3 | `runAsyncIfLeaderResult` 반환 = `CompletableFuture<LeaderRunResult<T>>` | `CompletableFuture<T?>` + 별도 `wasElected` future | sealed-result 일관성, idiomatic |
| O4 | Exposed `audit_leader_id VARCHAR(256)` (confirmed) | `VARCHAR(64)` 좁힘 | tenant+UUID 조합 대비 256 확정. 작은 storage cost 와 향후 확장 모두 합리적 |
| O5 | `LeaderElectionInfo.leaderId` default `""` | sealed type `Empty/Provided` | over-engineering — `""` empty sentinel 일관성 |

→ O4 확정: VARCHAR(256). User 합의 2026-05-12.

---

## 15. Acceptance Criteria / DoD

본 spec 의 DoD — 모든 항목이 8개 PR 통합 후 만족되어야 함:

- [ ] `LeaderSlot(lockName, leaderId)` value object 추가, `leader-core` 공개 API
- [ ] `LeaderIdProvider` 인터페이스 + 3개 구현 (`Random`, `HostnamePid`, `Composite`) 추가
- [ ] 8개 elector 인터페이스 (sync × group/single + suspend × g/s + async × g/s + VT × g/s) 에 `LeaderSlot` primary + Result variant 추가, binary-compat
- [ ] `LeaderLease.leaderId` 의미 변경 + `LeaderLease.nodeId` additive 필드 + Phase A0 grep gate 통과
- [ ] `LeaderRunResult.Elected.leaderId` 추가 (default `""`)
- [ ] `LeaderElectionInfo.leaderId` 추가 (default `""`)
- [ ] `LeaderLockHandle.Real.auditLeaderId` 추가 (excluded from equals/hashCode)
- [ ] 7+ backend (Local, Lettuce, Redisson, Mongo, Hazelcast, ZooKeeper, Exposed-JDBC, Exposed-R2DBC) 모두 audit 통과
- [ ] Exposed-JDBC/R2DBC schema 에 `audit_leader_id VARCHAR(256) NULL` 컬럼 additive 추가
- [ ] `@LeaderGroupElection.leaderId` + `@LeaderElection.leaderId` SpEL 필드 추가
- [ ] `LeaderAnnotationValidatorBeanPostProcessor` startup pre-parse 적용
- [ ] `LeaderAopProperties.defaultLeaderId` 추가 + auto-config 에서 default `LeaderIdProvider` bean 등록
- [ ] `LeaderGroupElectionAspect` + `LeaderElectionAspect` 가 `resolveLeaderId` fallback chain 적용 (literal → SpEL → property → provider)
- [ ] `AbstractLeaderIdContractTest` × 4 execution × 2 (single/group) = 최대 8 abstract testFixture 추가
- [ ] 각 backend 모듈 contract subclass 추가, `bluetape4k-junit5` concurrency tester 활용
- [ ] AOP integration test: literal / SpEL / property / provider / validator failure 5 케이스 통과
- [ ] Metrics test: `leader.id` tag 검증
- [ ] README locale set (`README.md` + `README.ko.md`) 모든 영향 모듈 업데이트, Mermaid diagram 추가
- [ ] CHANGELOG: `LeaderLease.leaderId` semantic change, `LeaderLease.nodeId` additive, Exposed migration, `Elected` destructuring 변경 모두 명시
- [ ] English KDoc on all new/changed public symbols (audience: contributor — English per policy)
- [ ] Kover 커버리지 `leader-core` ≥ 80%, backend ≥ 60%
- [ ] `detekt` clean across touched modules
- [ ] `actionlint` clean on any `.github/workflows/*.yml` 변경 (예상 없음)

---

## 16. Out of scope (deferred)

- Custom Redisson Lua semaphore (Approach 3 의 Redis backend redesign) — 별도 issue
- Multi-tenancy namespace prefix (issue #42) — `CompositeLeaderIdProvider` 가 부분 cover, 완전한 multi-tenancy 는 #42 본 작업
- `LeaderHistoryRecord` / audit sink contract (issue #50) — 본 spec 은 in-process audit 표면만 정의, 영구 audit log sink 는 #50 의 범위
- Async / VT 백엔드 dispatch 모델 변경 (lease auto-extend hook 등) — 본 spec 은 기존 모델 유지

---

## 17. Draft Task List (Phase A–K)

```text
Phase A — Core types (PR1)
  T1 [low]    LeaderSlot.kt — data class + validation
  T2 [low]    identity/LeaderIdProvider.kt — fun interface
  T3 [low]    identity/RandomLeaderIdProvider.kt — Base58 default
  T4 [low]    identity/HostnamePidLeaderIdProvider.kt — opt-in PII
  T5 [low]    identity/CompositeLeaderIdProvider.kt — prefix wrap
  T6 [med]    LeaderLease.kt — add nodeId, semantic shift KDoc
  T7 [med]    LeaderRunResult.kt — add leaderId="" to Elected
  T8 [med]    LeaderLockHandle.kt — add auditLeaderId, copy preserve
  T9 [low]    coroutines/LeaderElectionInfo.kt — add leaderId=""

Phase B — Core interfaces (PR1)
  T10 [high]  LeaderElector.kt + LeaderGroupElector.kt — LeaderSlot primary + Result variant
  T11 [high]  AsyncLeaderElector.kt + AsyncLeaderGroupElector.kt — primary + runAsyncIfLeaderResult
  T12 [high]  VirtualThreadLeader{Group,}Elector.kt — primary + runAsyncIfLeaderResult
  T13 [high]  coroutines/SuspendLeader{Group,}Elector.kt — primary + Result variant

Phase C — Local backend (PR1)
  T14 [high]  LocalLeaderStateRegistry.kt — split leaderId from nodeId
  T15 [high]  local/Abstract*Local{Group,}Elector.kt + Local{Async,Suspend,VT}*Elector.kt — propagate
  T16 [high]  testFixtures Abstract*LeaderIdContractTest × 4 execution × 2 (g/s) — RED
  T17 [med]   leader-core test/ — subclass contracts for Local, GREEN

Phase D — Lettuce + Redisson (PR2)
  T18 [high]  semaphore/LettuceSlotTokenGroup.kt — Lua HSET meta
  T19 [high]  Lettuce{Leader,GroupLeader,Suspend*}Elector.kt — propagate
  T20 [high]  RedissonLeader*Elector.kt + RMap<permitId,leaderId> 동행
  T21 [med]   leader-redis-{lettuce,redisson}/test — contract subclasses

Phase E — Mongo (PR3)
  T22 [high]  lock/MongoLock.kt — slot doc + leaderId field
  T23 [high]  Mongo{Leader,GroupLeader,Suspend*}Elector.kt — propagate
  T24 [med]   leader-mongodb/test — contract subclasses

Phase F1 — Hazelcast (PR4)
  T25 [high]  Hazelcast*Elector.kt + IMap value 변경
  T26 [med]   tests

Phase F2 — ZooKeeper (PR5)
  T27 [high]  Zookeeper*Elector.kt + znode JSON 변경
  T28 [med]   tests

Phase G — Exposed JDBC/R2DBC (PR6)
  T29 [med]   LeaderGroupLockTable.kt — audit_leader_id VARCHAR(256)
  T30 [high]  Exposed*Elector.kt (JDBC + R2DBC) — propagate
  T31 [med]   tests + Testcontainers Postgres migration test

Phase H — AOP annotation + aspect (PR7)
  T32 [low]   annotation/LeaderGroupElection.kt + LeaderElection.kt — add leaderId
  T33 [med]   aop/properties/LeaderAopProperties.kt — defaultLeaderId
  T34 [high]  aop/LeaderGroupElectionAspect.kt — resolveLeaderId + slot 구성
  T35 [high]  aop/LeaderElectionAspect.kt — mirror
  T36 [med]   aop/validator/LeaderAnnotationValidatorBeanPostProcessor.kt — preParse

Phase I — Spring auto-config (PR7)
  T37 [med]   aop/autoconfigure/LeaderAopFactoryAutoConfiguration.kt — default LeaderIdProvider bean

Phase J — Integration tests (PR8)
  T38 [high]  LeaderIdAopIntegrationTest.kt — 5 cases (literal/SpEL/prop/provider/validator)
  T39 [med]   LeaderIdMetricsTagsTest.kt
  T40 [med]   LeaderIdMultiBackendIntegrationTest.kt — cross-backend smoke

Phase K — Docs (PR8)
  T41 [med]   leader-core/README.{md,ko.md} — Audit Identity 섹션 + Mermaid
  T42 [med]   leader-spring-boot/README.{md,ko.md} — annotation 예시
  T43 [low]   각 backend README.{md,ko.md} 업데이트
  T44 [low]   Root README.{md,ko.md} — feature highlight
  T45 [med]   CHANGELOG.md — Unreleased 섹션, breaking notes
  T46 [low]   English KDoc 보강 (모든 변경 public symbol)
```

총 46 task. Complexity 분포: high 14, medium 16, low 16.

---

## Appendix — Iteration Log (Step 2-R will append)

| Round | Reviewer | Findings (P0/P1/P2/P3) | Commit hash | Notes |
|-------|----------|------------------------|-------------|-------|
| (placeholder — Step 2-R 시 채움) | | | | |
