# Spec — `LockExtender` / `LockAssert` 등가 API + Reentrant `@LeaderElection` + 명시적 lease 연장

> **Issue:** #79 — ShedLock-equivalent API + reentrant + explicit lease extension
> **Date:** 2026-05-10
> **Scope:** `leader-core`, `leader-spring-boot`, 8개 backend (Lettuce, Redisson, Mongo, Exposed JDBC/R2DBC, Hazelcast, ZooKeeper, Local)
> **Status:** Spec — Brainstorming Step 2-A 산출물 + Round 1 review 통합 (Codex + 4 perspective)

---

## 0. Prerequisites (확정 사항)

- [x] **AOP suspend / Mono 분기는 이미 지원됨** — `LeaderElectionAspect.aroundLeaderSuspend` (#90), `aroundLeaderMono` (#91) 가 main 에 머지된 상태. 두 CLAUDE.md (root + leader-bom) 의 "v1.x sync-only" 문구는 doc drift — 본 PR 에서 함께 갱신.
- [x] **`runIfLeader` 반환값 contract 불변** — null 은 "리더 미선출" 의미 유지. fail-open detection 은 `result == null` 가 아닌 명시적 `LeaderRunResult { Elected, Skipped }` sealed marker 또는 `actionExecuted: AtomicBoolean` flag 사용 (Codex F1).
- [x] **Absolute duration 채택** — `lockAtMostFor: Duration` (ShedLock 호환). Issue #79 의 "additional duration" 문구는 misnomer — §10.4 마이그레이션 가이드에 반영.
- [x] **8 backend 모두 lock instance closure 가능** — code-architect A12 검증 완료. `LeaderElector` 인터페이스 변경 없음.

---

## 1. Problem Statement

ShedLock 사용자가 bluetape4k-leader 로 마이그레이션할 때 **세 가지 ergonomic gap** 이 막힌다:

1. **`LockAssert.assertLocked()` 부재** — 어노테이션이 정말로 락을 잡았는지 비즈니스 로직 안에서 단언할 방법이 없다.
2. **`LockExtender.extendActiveLock(Duration)` 부재** — 작업이 예상보다 오래 걸릴 때 caller 가 명시적으로 lease 를 연장할 표준 API 가 없다. Watchdog 자동 연장(#73) 은 backend cadence 에 묶여 있고 사용자 의도를 표현하지 못한다.
3. **Reentrant `@LeaderElection` 미정의** — 동일 lockName 으로 annotated 메서드를 중첩 호출하면 안쪽 호출이 backend 를 두 번 두드린다. 결과는 backend 별로 다르며 (Lettuce SETNX false, Mongo token 충돌 → fail-open, Redisson reentrant true) 사용자 코드는 정상으로 보이지만 race window 가 발생한다.

bluetape4k-leader 의 elector 인터페이스 6개 모두 lock handle 을 외부에 노출하지 않는다. 이 캡슐화 자체는 좋지만 ShedLock 의 thread-local 기반 패턴(`LockProvider` 가 lock stack 을 ThreadLocal 에 push) 으로 정확히 대응 가능하므로, **현재 elector 인터페이스를 변경하지 않고** `LockAssert` / `LockExtender` 를 추가할 수 있다 — single source of insight.

핵심 질문 (Round 1 review 결과 강화):
- **누가 handle 을 들고 있는가?** ShedLock 답: ThreadLocal Deque. caller 는 backend 인스턴스를 만질 필요가 없다.
- **handle 은 어떻게 backend 의 atomic extend 를 호출하는가?** Closure 로 backend lock 의 `extend(leaseTime)` 를 캡처. **suspend 백엔드는 `suspendExtendFn` 별도 보유** (Codex F2).
- **suspend / Mono 컨텍스트는?** ThreadLocal 부정확 → `CoroutineContext.Element` 사용. **별도 `LockHandleElement`** 로 binary-compat 보존 (type T8 / Codex F5).

---

## 2. Goals / Non-Goals

### Goals
- ShedLock-호환 ergonomic API: `LockAssert.assertLocked()`, `LockExtender.extendActiveLock(Duration)` (Java + Kotlin sync, suspend).
- Reentrant `@LeaderElection` / `@LeaderGroupElection` — 동일 **lock identity** (lockName + 어노테이션 종류 + factory bean + group params) 의 중첩 진입 시 backend acquire 호출은 정확히 1회 (Codex F3).
- 8 backend 모두 atomic `extend(leaseTime: Duration): Boolean` 또는 `suspend fun extend(...)` API 구비 — token guard 필수.
- AOP 통합: sync, suspend, Mono 3 분기에서 `LockAssert` / `LockExtender` 모두 동작.
- Watchdog (#73) 와 race-free 공존 + **last-write-wins 가시성** (metric + WARN log).
- Fail-open 모드 (`failureMode = FAIL_OPEN_RUN`) — contention 분기 + backend exception 분기 모두에서 sentinel push. `SKIP` 모드는 sentinel push 없이 `null` 반환, `RETHROW` 는 throw (R2-F1).
- `@LeaderElection` 이 `CompletableFuture` 반환 메서드에 적용된 경우 **validator 가 차단** (Codex F9 / Architect A4).

### Non-Goals (v1)
- `AsyncLeaderElector.runAsyncIfLeader` 직접 호출.
- `VirtualThreadLeaderElector.runAsyncIfLeader` 직접 호출.
- `leader-ktor` plugin 통합 — `LockAssertSuspend` 만 자동 동작 (coroutineContext propagate). README 한 줄 노트만 추가.
- Multi-process reentrant lock — 분산 reentrancy 비목표.
- ShedLock `@SchedulerLock(lockAtLeastFor)` minLeaseTime override — 이미 `LeaderElectionOptions.leaseTime` + backend `minLeaseTime` 구현 (#108).

---

## 3. Risks / Failure Modes

| # | 위험 | 영향 | 완화 |
|---|------|------|------|
| R1 | **Reentrant dedupe 잘못된 위치 — elector 레벨에 두면 cross-elector deadlock** | A elector 로 lock A 잡고, 같은 thread 에서 다른 elector 인스턴스로 lock A 다시 시도 → 두 번째 호출이 backend acquire 로 빠져 timeout | Aspect 레벨 + **full lock identity peek** (lockName + annotation kind + factory bean) — Codex F3 |
| R2 | **Hazelcast `IMap.setTtl` lost-update race** | 토큰 검사 없이 TTL 만 갱신 | 반드시 `EntryProcessor` 내부에서 `(token == ours && expireAt > now)` 검사 후 atomic 갱신. plain `setTtl` 금지 + AC-7 grep verify |
| R3 | **Redisson `RLock.expire` 의 owner-atomic 미보장** | `isHeldByThread` 검사 후 `expire` 호출 사이에 lease 만료 + 다른 thread 가 acquire → wrong owner extend | **owner-guarded Lua** (`HEXISTS k thread → HSET expire`) 사용. Redisson 내장 `RLockEntry`/`tryLockExpireAsync` 또는 자체 Lua 작성 (Codex F8) |
| R4 | **FAIL_OPEN sentinel handle 이 real handle 로 오인** | 사용자가 silent false 보고 작업 계속 | **Sealed class `LeaderLockHandle.FailOpen` ≠ `LeaderLockHandle.Real`** — 컴파일러 exhaustive check 강제 (Type T3) |
| R5 | **Watchdog 가 사용자 명시적 extend 를 silently 덮어쓴다** | `extendActiveLock(60s)` 직후 watchdog 이 `lease/3` 시점에 `lease=30s` 로 setExpire → 사용자 의도 60s 가 30s 로 축소 | (a) `extendActiveLock(d)` 호출 시 watchdog 활성 + `d > watchdog lease` 면 WARN log + metric `lock_extender_overridden_total` (b) AC-6b 추가: "user-extended TTL 가 watchdog 다음 tick 에서 보호" — `LeaderLeaseAutoExtender` 가 handle 의 마지막 extend deadline 검사 후 max(watchdog cadence, user deadline) 사용 |
| R6 | **MongoDB `MongoLock.extend` 가 expired 문서 부활** | 현재 `MongoLock.extend` filter 가 `{_id, token}` 만 검사 → TTL 만료 후 cleanup 전 시점에 token 일치하면 부활 가능 (Codex F6) | filter 에 **`expireAt: { $gt: now }`** 추가 — atomic guard. 기존 `extend` 코드 수정 (private 변경 → internal + filter 강화) |
| R7 | **Suspend → Mono ThreadLocal 누수** | aspect sync 분기에서 push 한 ThreadLocal 이 IO dispatcher carrier 에서 다른 coroutine 에 노출 | suspend `assertLockedSuspend` 는 **`coroutineContext` only** — ThreadLocal fallback 제거 (Codex F13 / SF7) |
| R8 | **`LeaderElectionInfo` data class 변경 시 binary-compat 손상** | 외부 caller 가 `componentN()` / `copy()` 사용 중이면 NoSuchMethodError | **별도 `LockHandleElement: CoroutineContext.Element` 도입** — `LeaderElectionInfo` 무변경 (Type T8 / Codex F5). element 2개 동기화는 aspect 단일 위치에서만 수행 |
| R9 | **`extendFn: (Duration) -> Boolean` 가 suspend 백엔드 blocking** | R2DBC / Mongo suspend / Lettuce suspend 가 `withContext(IO)` 안에서 sync 호출 시 cancellation 무시 | **`LeaderLockHandle.Real` 에 `extendFn` 과 `suspendExtendFn` 모두 보유** — sync caller 는 전자, suspend caller 는 후자. `runCatching` 금지, 명시적 `try { ... } catch(CancellationException) { throw e } catch(Exception) { ... }` (Codex F2) |
| R10 | **`LeaderLockHandleCapture` ThreadLocal 누락 시 silent sentinel** | elector 가 set 누락하면 사용자 코드가 fail-open sentinel 로 silently 동작 → split-brain | capture 누락 = **IllegalStateException** (`error("elector did not capture handle — bug in <elector class>")`) — fail-fast (SF3 / Codex F10) |
| R11 | **Sync extendFn Boolean 정보 손실** | 4가지 실패 (token mismatch / expired / wrong thread / backend error) 가 모두 false → 운영 분류 불가 | **`sealed class ExtendOutcome { Extended(newExpireAt), NotHeld, WrongThread, BackendError(cause) }`** 도입. `LockExtender.extendActiveLockDetailed(d): ExtendOutcome` 추가 (legacy `Boolean` API 는 wrapper 로 유지) (Type T2 / SF2) |
| R12 | **`@LeaderElection` 이 `CompletableFuture` 반환 메서드에 적용** | aspect 가 sync 분기로 처리 → action 종료 = release 가 future 완료 전 | **`LeaderAnnotationValidatorBeanPostProcessor` 가 returnType ∈ {CompletableFuture, Future, ListenableFuture} → strict throw / non-strict WARN** (Codex F9) |
| R13 | **Group reentrant 의미론 미정의** | `@LeaderGroupElection` 동일 lockName 중첩 호출 시 backend 동작 불명 | aspect 의 reentrant peek 은 **lockName + annotation kind 동시 일치** 시 passthrough — Single ↔ Group 간 dedupe 차단. Group nested 시 outer 의 `slotId` 재사용 (Architect A5) |
| R14 | **Lettuce group extend Lua client-side now 시 clock skew** | acquire/release 는 `redis.call('TIME')` 사용 (#151 lessons) 인데 extend 만 client now 사용 시 unanalyzable race | extend Lua 도 **server-side `redis.call('TIME')` 사용 강제** + AC 에 grep 검증 (Codex F7 / Reviewer F5) |
| R15 | **Java caller 가 `kotlin.time.Duration` 받음** | ShedLock Java 사용자 마이그레이션 시 API 불호환 | **`@JvmStatic` overload `extendActiveLock(java.time.Duration)`** 추가. Kotlin 사용자는 `kotlin.time.Duration` 사용 (Codex F4) |
| R16 | **ZK + watchdog 활성 시 noop watchdog tick** | ZK 는 TTL 없음 — watchdog 가 의미 없는 noop 호출 반복 | ZK elector 는 `LeaderLeaseAutoExtender.start(enabled = false)` 강제 호출 또는 ZK extend 가 항상 `Extended(neverExpires)` 반환 (Architect A7) |

---

## 4. Approaches (decision-by-decision)

### 4.1 Handle 노출 방식

| Option | 설명 | 장점 | 단점 |
|---|---|---|---|
| **A. Sealed class `LeaderLockHandle { Real, FailOpen }` (chosen)** | 기존 closure-based 변형 — `Real` 은 `extendFn` + `suspendExtendFn` 보유, `FailOpen` 은 sentinel | 컴파일러 exhaustive check, sentinel ≠ Real, internal constructor → 외부 fake 차단 | data class 의 자동 `copy/equals` 손실 — 명시적 reentrantCopy 메서드 필요 |
| B. data class + private 람다 | 단순 | `equals/hashCode` 가 람다 ref 비교 (불의미), `copy(token=...)` 외부 호출 가능 (R4 footgun) | type T1+T3+T4 high finding |
| C. Marker interface `BackendLock` | 모든 backend lock 이 인터페이스 구현 | 정적 타입 안전 | 8 backend 침습적 |

→ **A 채택** — Round 1 type T1/T3/T4 통합.

### 4.2 Reentrant dedupe 위치 + identity

| Option | 설명 |
|---|---|
| **A. Aspect 레벨 + full lock identity (chosen)** | `(lockName, annotationKind, factoryBean, groupParams)` 4-tuple 일치 시 passthrough |
| B. Aspect + lockName only | 원래 spec — Single ↔ Group 간 잘못 dedupe (Codex F3) |

→ **A 채택**.

### 4.3 Suspend / Mono propagation

| Option | 설명 | 장점 | 단점 |
|---|---|---|---|
| **A. 별도 `LockHandleElement` (chosen)** | `LeaderElectionInfo` 무변경, 새 element 추가 | binary-compat 보존, 의미 분리 명확 | aspect 가 element 2개 push (`+` 결합) |
| B. `LeaderElectionInfo` 에 `handle` 추가 | element 1개 | data class binary-compat 손상 (R8 / Codex F5) |
| C. `ThreadContextElement` | suspend 안에서 sync API 호출 가능 | 매 dispatch 마다 push/pop 비용 |

→ **A 채택** — type T8 / Codex F5 통합.

### 4.4 Fail-open 모드 동작

| Option | 설명 |
|---|---|
| **A. Sentinel handle push (chosen)** | contention + backend exception 두 분기 모두에서 `LeaderLockHandle.FailOpen(lockName)` push |
| B. push 안 함 — `assertLocked` IllegalStateException, `extend` false |
| C. `assertLocked` throw, `extend` false (push 안 함) |

→ **A 채택** + **backend-exception fail-open 분기 명시** (Codex F10 / Architect A2).

### 4.5 ZooKeeper extend 의미

→ **Passthrough `mutex.isAcquiredInThisProcess()`** + watchdog noop 강제 (R16).

### 4.6 LockExtender result type

| Option | 설명 |
|---|---|
| **A. Boolean wrapper + Detailed sealed (chosen)** | `extendActiveLock(d): Boolean` (ShedLock 호환) + `extendActiveLockDetailed(d): ExtendOutcome` 동시 노출 |
| B. Boolean only | 정보 손실 (R11) |
| C. Detailed only | ShedLock 1:1 호환 깨짐 |

→ **A 채택** — type T2 / SF2 통합.

### 4.7 Java Duration support

→ **`@JvmStatic` overload `(java.time.Duration)` 추가** (R15).

---

## 5. API Surface

### 5.1 `LockAssert` / `LockExtender` (top-level objects)

```kotlin
// leader-core/src/main/kotlin/io/bluetape4k/leader/LockAssert.kt
package io.bluetape4k.leader

import io.bluetape4k.leader.coroutines.LockHandleElement
import io.bluetape4k.leader.internal.LockStateHolder
import kotlin.coroutines.coroutineContext

/**
 * 현재 컨텍스트가 활성 `@LeaderElection` / `@LeaderGroupElection` 본문 안에서 실행 중인지 단언합니다.
 *
 * ShedLock 의 `LockAssert.assertLocked()` 와 동일한 사용감을 제공합니다.
 *
 * ## 동작/계약
 * - 활성 lock state 없음 → [IllegalStateException]
 * - fail-open sentinel scope → [IllegalStateException] (fail-open 본문은 락이 없는 상태)
 * - reentrant 진입 시에도 outer 가 sentinel 이 아니면 통과
 *
 * ## Cross-context 동작 (suspend → sync API 호출)
 * **suspend 컨텍스트에서는 [assertLockedSuspend] 호출**. sync `assertLocked()` 를 suspend 안에서 호출 시
 * carrier thread 의 ThreadLocal 만 검사 → 잘못된 handle 노출 위험 (R7).
 *
 * ## Example
 * ```kotlin
 * @LeaderElection(name = "report-job")
 * fun runReport() {
 *     LockAssert.assertLocked()       // ✅ passes
 * }
 * ```
 */
object LockAssert {
    @JvmStatic fun assertLocked() { ... }
    @JvmStatic fun assertLocked(lockName: String) { ... }
    @JvmStatic fun isLocked(): Boolean = LockStateHolder.peekSync()
        ?.let { it !is LeaderLockHandle.FailOpen } ?: false
    @JvmStatic fun isLocked(lockName: String): Boolean { ... }

    suspend fun assertLockedSuspend() { ... }
    suspend fun assertLockedSuspend(lockName: String) { ... }
    suspend fun isLockedSuspend(): Boolean { ... }
    suspend fun isLockedSuspend(lockName: String): Boolean { ... }

    /** suspend 전용 — coroutineContext only, ThreadLocal fallback 없음 (R7 / Codex F13) */
    private suspend fun currentRealHandleSuspend(): LeaderLockHandle.Real? =
        coroutineContext[LockHandleElement]?.handle as? LeaderLockHandle.Real
}
```

```kotlin
// leader-core/src/main/kotlin/io/bluetape4k/leader/LockExtender.kt
package io.bluetape4k.leader

import io.bluetape4k.leader.coroutines.LockHandleElement
import io.bluetape4k.leader.internal.LockStateHolder
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration
import kotlin.time.toKotlinDuration

/**
 * 활성 `@LeaderElection` 컨텍스트의 lease 를 명시적으로 연장합니다.
 *
 * ## 동작/계약
 * - 활성 컨텍스트 없음 → `false` 반환 + WARN log + metric `lock_extender_no_active_scope_total` (SF1)
 * - fail-open sentinel → `false` 반환 + WARN log
 * - backend extend 실패 → `false` (token mismatch / expired / wrong thread / backend exception)
 * - **absolute** lease — `lockAtMostFor` 만큼 새 expire time 설정. 이슈 #79 의 "additional duration" 은 misnomer.
 *
 * ## Detailed result
 * 운영 가시성이 필요하면 [extendActiveLockDetailed] 사용 — `ExtendOutcome` sealed result 반환 (R11).
 *
 * ## Boolean ↔ Detailed 변환 contract (R2-F6)
 * - `extendActiveLock(d): Boolean` ≡ `extendActiveLockDetailed(d).isExtended`
 * - `Extended` → true
 * - `NotHeld` / `WrongThread` → false (WARN log + metric)
 * - `BackendError` (transient — `SQLTransientException`, `MongoSocketException` 등) → false (WARN log + metric)
 * - `BackendError` (non-transient — schema, auth) → throw (caller 가 결정)
 *
 * ## mismatched lockName 처리 (R2-F7)
 * - `extendActiveLock(lockName, d)` 의 `lockName` 이 현재 active handle 과 다르면 → `false` + WARN log
 *   ("LockExtender.extendActiveLock(name='X') called but active lock is 'Y'")
 * - `extendActiveLockDetailed(lockName, d)` 동일 시나리오 → `NotHeld`
 * - 오용 탐지를 원하면 `LockAssert.assertLocked(lockName)` 먼저 호출 권장
 *
 * ## Group elector 의미 (R2-F8)
 * `@LeaderGroupElection` 본문 안에서 호출 시 — **현재 보유한 slot 만** 의 lease 를 연장.
 * `lockName` 인자는 group name (전체 group 의 lockName) 이며, slotId 직접 지정 API 는 노출되지 않음.
 * 다른 slot 의 lease 를 갱신하는 것은 token guard 로 차단 (NotHeld 반환).
 *
 * ## 동시성 (Watchdog × LockExtender)
 * - 둘 다 atomic backend extend 호출 → race-free, 단 **last-write-wins**.
 * - `extendActiveLock(d)` 호출 시 watchdog 활성 + `d > watchdog lease` → WARN + metric (R5).
 * - 정확한 TTL 보호가 필요하면 watchdog OFF 권장 (= ShedLock 등가 모드).
 */
object LockExtender : KLogging() {
    @JvmStatic fun extendActiveLock(lockAtMostFor: Duration): Boolean { ... }
    @JvmStatic fun extendActiveLock(lockAtMostFor: java.time.Duration): Boolean =
        extendActiveLock(lockAtMostFor.toKotlinDuration())  // R15
    @JvmStatic fun extendActiveLock(lockName: String, lockAtMostFor: Duration): Boolean { ... }
    @JvmStatic fun extendActiveLock(lockName: String, lockAtMostFor: java.time.Duration): Boolean =
        extendActiveLock(lockName, lockAtMostFor.toKotlinDuration())

    @JvmStatic fun extendActiveLockDetailed(lockAtMostFor: Duration): ExtendOutcome { ... }
    @JvmStatic fun extendActiveLockDetailed(lockName: String, lockAtMostFor: Duration): ExtendOutcome { ... }

    suspend fun extendActiveLockSuspend(lockAtMostFor: Duration): Boolean { ... }
    suspend fun extendActiveLockSuspend(lockName: String, lockAtMostFor: Duration): Boolean { ... }
    suspend fun extendActiveLockDetailedSuspend(lockAtMostFor: Duration): ExtendOutcome { ... }
    suspend fun extendActiveLockDetailedSuspend(lockName: String, lockAtMostFor: Duration): ExtendOutcome { ... }  // R4-F6 — sync API 와 대칭
}
```

### 5.2a `LockIdentity` — full reentrant identity (R2-F3 / Codex F3)

```kotlin
// leader-core/src/main/kotlin/io/bluetape4k/leader/LockIdentity.kt
package io.bluetape4k.leader

/**
 * Reentrant dedupe 의 비교 단위. lockName 만으로는 Single ↔ Group 구분 불가 → full identity 필요.
 *
 * @property lockName SpEL 평가 후 resolved string
 * @property kind 어노테이션 종류 — `SINGLE` (`@LeaderElection`) / `GROUP` (`@LeaderGroupElection`)
 * @property factoryBeanName Spring bean name — 같은 lockName 이라도 다른 factory 면 별개 lock.
 *   **Branch별로 사용되는 factory bean 이 다름** (R4-F3): sync = `LeaderElector` factory, suspend = `SuspendLeaderElector` factory,
 *   group = `LeaderGroupElector` factory, suspend-group = `SuspendLeaderGroupElector` factory.
 *   `AdviceMetadata.resolveLockIdentity(branch)` 가 branch 에 맞는 bean name 을 LockIdentity 에 설정.
 * @property groupParams Group 의 식별 파라미터 (Single 이면 null) — **현재 `maxLeaders` 만 보유** (R3-F7).
 *   slot strategy / weight 등 추가 옵션은 향후 확장 시 이 클래스에 필드 추가 (binary-compat 위해 default 값 사용).
 */
data class LockIdentity(
    val lockName: String,
    val kind: AnnotationKind,
    val factoryBeanName: String,
    val groupParams: GroupParams? = null,
) {
    enum class AnnotationKind { SINGLE, GROUP }
    /** Currently `maxLeaders` only — future fields require default values for binary-compat. */
    data class GroupParams(val maxLeaders: Int)
}
```

`LeaderLockHandle.Real` 와 `FailOpen` 모두 `identity: LockIdentity` 필드 보유 — `matchesIdentity(other)` 는 `this.identity == other.identity`.

### 5.2b `LeaderLockHandle` (sealed class — Type T1+T3+T4)

```kotlin
// leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderLockHandle.kt
package io.bluetape4k.leader

import kotlin.time.Duration

/**
 * 활성 lock 의 handle. AOP aspect 가 push 하고 `LockAssert` / `LockExtender` 가 read.
 *
 * ## Variants
 * - [Real] — 실제 backend 보유. `extend()` / `extendSuspend()` 호출 가능
 * - [FailOpen] — fail-open sentinel. extend 항상 `NotHeld` 반환
 *
 * **소스 API 레벨에서** 외부 생성 차단 — `internal constructor`. AOP aspect 또는 elector 만 생성.
 * (R3-F15: `internal` 은 reflection / security boundary 아님 — 신뢰된 caller 한정 source API 차단 의미.)
 */
sealed class LeaderLockHandle {
    abstract val identity: LockIdentity                 // ⭐ R2-F3 — full identity 필수
    val lockName: String get() = identity.lockName
    abstract val reentryDepth: Int
    val isReentrant: Boolean get() = reentryDepth > 0
    fun matchesIdentity(other: LockIdentity): Boolean = identity == other

    class Real internal constructor(
        override val identity: LockIdentity,
        val token: String,
        val acquiredAtNanos: Long,
        val slotId: String? = null,
        val acquiringThreadId: Long? = null,            // Redisson 만 non-null
        override val reentryDepth: Int = 0,
        internal val extendDelegate: ExtendDelegate,    // ⭐ R2-F4 — watchdog 와 reference 동일성 보장
    ) : LeaderLockHandle() {
        /** sync extend — `Dispatchers.IO` 호출이 필요한 backend 는 내부에서 처리. suspend caller 는 [extendSuspend] 사용. */
        fun extend(d: Duration): ExtendOutcome = extendDelegate.extend(d)

        /** suspend extend — backend 가 suspend native 면 non-blocking, 아니면 `withContext(IO)` 위임. */
        suspend fun extendSuspend(d: Duration): ExtendOutcome = extendDelegate.extendSuspend(d)

        fun isStillHeld(): Boolean = extendDelegate.isHeld()

        /**
         * Reentrant passthrough copy 생성. **inner extend 는 outer 의 `extendDelegate` 를 그대로 호출
         * → outer/backend lease 가 갱신됨** (R2-F12 / SF11).
         */
        internal fun withReentryDepth(n: Int): Real {
            require(n >= 0) { "reentryDepth must be >= 0" }
            return Real(identity, token, acquiredAtNanos, slotId, acquiringThreadId, n, extendDelegate)
        }

        // 명시적 equals/hashCode/toString — delegate 제외, (identity, token, reentryDepth, slotId) 기반
        override fun equals(other: Any?): Boolean { ... }
        override fun hashCode(): Int { ... }
        override fun toString(): String =
            "LeaderLockHandle.Real(identity=$identity, token='$token', reentryDepth=$reentryDepth, slotId=$slotId)"
    }

    class FailOpen internal constructor(
        override val identity: LockIdentity,            // ⭐ R2-F3 — sentinel 도 identity 보유
    ) : LeaderLockHandle() {
        override val reentryDepth: Int = 0
        override fun toString(): String = "LeaderLockHandle.FailOpen(identity=$identity)"
    }

    companion object {
        internal fun real(...): Real = Real(...)
        internal fun failOpen(identity: LockIdentity): FailOpen = FailOpen(identity)
    }
}
```

### 5.3 `ExtendOutcome` (sealed result — Type T2 / R11)

```kotlin
// leader-core/src/main/kotlin/io/bluetape4k/leader/ExtendOutcome.kt
package io.bluetape4k.leader

import java.time.Instant

/**
 * `LeaderLockHandle.Real.extend` 의 상세 결과. Boolean 시그니처는 `is Extended` 으로 단순 변환 가능.
 */
sealed interface ExtendOutcome {
    /**
     * extend 성공.
     *
     * @property observedExpireAt **best-effort** new expire time. backend 별 정확도 (R2-F5):
     * - Lettuce / Hazelcast / Local: server-side 시각 사용 → 정확
     * - Redisson: Redisson 내부 atomic — Redisson 이 client clock 사용 가능 → ±50ms
     * - MongoDB: server-side `$set` — 정확
     * - Exposed JDBC/R2DBC: DB server 시각 (`now()` SQL) — 정확
     * - ZooKeeper: TTL 개념 없음 — `observedExpireAt = Instant.MAX` 로 표현하지만 의미는 "session-held liveness 확인" passthrough (R3-F11). lease extension 아님.
     *
     * caller 는 정확한 deadline 으로 사용 X — observability/logging 용.
     */
    data class Extended(val observedExpireAt: Instant) : ExtendOutcome
    data object NotHeld : ExtendOutcome              // token mismatch / lease expired / takeover
    data object WrongThread : ExtendOutcome          // Redisson thread-bound
    data class BackendError(val cause: Throwable) : ExtendOutcome  // transient: false 변환, non-transient: throw

    val isExtended: Boolean get() = this is Extended
}
```

### 5.3a `ExtendDelegate` — watchdog 와 LockExtender 의 단일 진입점 (R2-F4 / Architect A3)

```kotlin
// leader-core/src/main/kotlin/io/bluetape4k/leader/internal/ExtendDelegate.kt
package io.bluetape4k.leader.internal

import io.bluetape4k.leader.ExtendOutcome
import kotlin.time.Duration

/**
 * Backend lock 의 atomic extend 를 단일 reference 로 제공. `LeaderLockHandle.Real` 와
 * `LeaderLeaseAutoExtender` (Watchdog #73) 모두 동일 `ExtendDelegate` 를 참조 — race-free.
 *
 * AC-15 검증: `handle.extendDelegate === watchdog.delegate` (reference 동일성).
 */
internal interface ExtendDelegate {
    fun extend(lockAtMostFor: Duration): ExtendOutcome

    /**
     * suspend extend.
     *
     * **default 구현은 sync `extend` 직접 호출 — local 또는 non-blocking backend 전용.**
     * Blocking backend (Lettuce sync, Hazelcast IMap, Exposed JDBC, Redisson 등) 는 반드시 override
     * 하여 `withContext(Dispatchers.IO)` + `coroutineContext.ensureActive()` 사용 (R3-F8 / R9).
     *
     * AC-21: blocking backend 의 `ExtendDelegate.extendSuspend` 가 default 호출 0회 (소스 grep verify).
     */
    suspend fun extendSuspend(lockAtMostFor: Duration): ExtendOutcome = extend(lockAtMostFor)

    fun isHeld(): Boolean
}
```

각 elector 가 acquire 직후 단일 `ExtendDelegate` 인스턴스 생성 → watchdog `.start(delegate = ...)` + `LeaderLockHandle.Real(extendDelegate = ...)` 둘 다 동일 reference 전달. `LeaderLeaseAutoExtender.start` 시그니처 변경 — `(Duration) -> Boolean` 람다 → `ExtendDelegate` 객체.

### 5.4 Internal mechanisms

```kotlin
// leader-core/src/main/kotlin/io/bluetape4k/leader/internal/LockStateHolder.kt
internal object LockStateHolder {
    private val tl: ThreadLocal<ArrayDeque<LeaderLockHandle>> = ThreadLocal.withInitial { ArrayDeque() }

    fun push(handle: LeaderLockHandle) { tl.get().push(handle) }
    fun pop(): LeaderLockHandle? = tl.get().pollFirst()
    fun peekSync(): LeaderLockHandle? = tl.get().peekFirst()
    fun peekSyncMatching(lockName: String): LeaderLockHandle? =
        tl.get().firstOrNull { it.lockName == lockName }
    fun cleanup() { if (tl.get().isEmpty()) tl.remove() }

    /** push/pop 헬퍼 — 누수 차단 */
    inline fun <R> withPushed(handle: LeaderLockHandle, block: () -> R): R {
        push(handle); try { return block() } finally { pop(); cleanup() }
    }
}
```

```kotlin
// leader-core/src/main/kotlin/io/bluetape4k/leader/internal/LeaderLockHandleCapture.kt
/**
 * Elector → Aspect 간 handle 전달용 ThreadLocal.
 *
 * ## 엄격한 invariant (R10)
 * - elector 는 acquire 후 action 호출 **직전** 동일 thread 에서 [set] 호출.
 * - aspect 는 action lambda 의 **첫 statement** 로 [poll] 호출.
 * - capture 누락 (poll 결과 null) → IllegalStateException ("elector did not capture handle — bug")
 *   silent fallback to FailOpen 절대 금지 (SF3 / Codex F10).
 * - virtual thread / dispatcher hop 사이에 set/poll 분리 금지.
 */
internal object LeaderLockHandleCapture {
    private val tl: ThreadLocal<LeaderLockHandle.Real?> = ThreadLocal()
    fun set(handle: LeaderLockHandle.Real) { tl.set(handle) }
    fun poll(): LeaderLockHandle.Real? = tl.get().also { tl.remove() }
    fun clear() { tl.remove() }
}
```

### 5.5 `LockHandleElement` — `CoroutineContext.Element` (R8 / Type T8)

```kotlin
// leader-core/src/main/kotlin/io/bluetape4k/leader/coroutines/LockHandleElement.kt
package io.bluetape4k.leader.coroutines

import io.bluetape4k.leader.LeaderLockHandle
import kotlin.coroutines.CoroutineContext

/**
 * suspend / Mono 컨텍스트의 active lock handle. `LeaderElectionInfo` 와 별도 element —
 * binary-compat 보존 (`LeaderElectionInfo` 무변경).
 */
/**
 * suspend / Mono 컨텍스트의 active lock handle.
 *
 * **`handle` property 는 `internal`** (R3-F12) — 외부 caller 는 `LockAssert.assertLockedSuspend()` /
 * `LockExtender.extendActiveLockSuspend(d)` API 만 사용. handle metadata (token, slotId 등) 직접 접근 차단.
 */
data class LockHandleElement internal constructor(internal val handle: LeaderLockHandle) : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<LockHandleElement>
    override val key: CoroutineContext.Key<*> get() = Key
}
```

### 5.6 `LeaderElectionInfo` — **무변경**

기존 2-arg data class 그대로. binary-compat 보존.

### 5.7 `LeaderRunResult` — fail-open detection (Codex F1 / R2-F2)

### 정책 (R3-F3 정합성 정리)

**`LeaderRunResult` — 기존 public sealed 그대로 사용** (수정 없음):

```kotlin
// leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderRunResult.kt (이미 존재)
sealed interface LeaderRunResult<out T> {
    data class Elected<out T>(val value: T?) : LeaderRunResult<T>
    data object Skipped : LeaderRunResult<Nothing>
}
```

- **backend exception variant 추가하지 않음** — aspect 가 `runIfLeaderResult` 호출을 `try/catch (Exception)` 로 감싸 직접 캡처.
- **인터페이스 default fun 변경 없음** — `runIfLeaderResult(lockName, action)` 는 `LeaderElector`/`LeaderGroupElector` 에 이미 default fun 으로 존재 (현재 sync 시그니처: `fun <T> runIfLeaderResult(lockName: String, action: () -> T): LeaderRunResult<T>`). 본 PR 에서 신규 추가 X.
- **suspend 변형 default fun 추가** (R4-F1):

```kotlin
// SuspendLeaderElector.kt — default fun 신규 추가 (R5-F1)
// ⭐ `elected` flag 패턴 — action 이 정상 실행 후 null 반환해도 Elected 분류
//   (기존 sync `runIfLeaderResult` default fun 의 LeaderElector.kt:62 패턴 동일)
suspend fun <T> runIfLeaderResultSuspend(
    lockName: String,
    action: suspend () -> T,
): LeaderRunResult<T> {
    var elected = false
    val value = runIfLeader(lockName) { elected = true; action() }
    return if (elected) LeaderRunResult.Elected(value) else LeaderRunResult.Skipped
}

// SuspendLeaderGroupElector.kt — 동일 패턴
suspend fun <T> runIfLeaderResultSuspend(
    lockName: String,
    action: suspend () -> T,
): LeaderRunResult<T> {
    var elected = false
    val value = runIfLeader(lockName) { elected = true; action() }
    return if (elected) LeaderRunResult.Elected(value) else LeaderRunResult.Skipped
}
```

- ⚠️ **`options` 인자 spec sketch 에서 제거** (R4-F1) — 기존 `runIfLeader` API 가 `options` 를 별도 인자로 받지 않고 `LeaderElectionOptions` 는 elector 생성 시점 또는 어노테이션 메타데이터로 전달. spec §7.1/§7.2 sketch 의 `meta.options` 인자는 **메타데이터 전달 용 (caller side context)** 이며, 실제 `runIfLeaderResult*` 호출 시 인자로 넣지 않음. (default fun 이 기존 `runIfLeader(name, action)` 만 호출.)
- **default 구현 보유 → 기존 구현체 binary-compat 보존** (Kotlin default fun 은 caller-side 호환 — 새 구현 없이도 동작).

**적용 범위 매트릭스** (R2-F2 / R3-F3):

| Elector 종류 | `runIfLeaderResult*` | 본 PR 영향 |
|---|---|---|
| `LeaderElector` (sync) | 이미 default fun (`runIfLeaderResult`) | 변경 없음 — aspect 가 직접 호출 |
| `SuspendLeaderElector` | **default fun 신규 추가 (`runIfLeaderResultSuspend`)** | source-compat (default 보유), binary 영향 미미 (interface 추가 default — 기존 .class 호환) |
| `LeaderGroupElector` | 이미 default fun | 변경 없음 |
| `SuspendLeaderGroupElector` | **default fun 신규 추가** | 동일 |
| `AsyncLeaderElector` / `VirtualThreadLeaderElector` | 추가 안 함 (out-of-scope) | — |

**§10.1 source-compat 갱신**: "모든 elector 인터페이스 무변경" → "**`SuspendLeaderElector`/`SuspendLeaderGroupElector` 에 default fun 추가** (source/binary 호환), 그 외 무변경".

---

## 6. Backend `extend` Contract Table

| Backend | 파일 | 메서드 시그니처 | Atomicity 메커니즘 | 신규/기존 |
|---|---|---|---|---|
| **Lettuce single sync** | `leader-redis-lettuce/.../lock/LettuceLock.kt` | `fun extend(leaseTime: Duration): ExtendOutcome` | 기존 Lua `EXTEND_SCRIPT` (`IF GET k == token THEN PEXPIRE`) — server-side TTL | 기존 (반환형만 변경) |
| **Lettuce single suspend** | `LettuceSuspendLock.kt` | `suspend fun extend(...): ExtendOutcome` | 동일 + `withContext(IO)` + `ensureActive()` | 기존 검증 |
| **Lettuce group** | `leader-redis-lettuce/.../slot/LettuceSlotTokenGroup.kt` | `fun extendSlot(token, leaseTime): ExtendOutcome` (+ suspend variant) | **신규 Lua, server-side `redis.call('TIME')` 사용** (R14 / Codex F7): `local t=redis.call('TIME'); local nowMs=t[1]*1000+math.floor(t[2]/1000); IF ZSCORE k tok > nowMs THEN ZADD XX k (nowMs+lease) tok PEXPIRE k (lease+5000) RETURN 1 ELSE RETURN 0` | **신규** |
| **Redisson single** | `leader-redis-redisson/.../RedissonLeaderElector.kt` 내부 `RLock` | `fun extend(...): ExtendOutcome` | **owner-guarded Lua** (`HEXISTS lock thread → HSET lock thread expire`) — `RLockEntry.expireAsync(...)` 또는 자체 Lua. 단순 `RLock.expire` 금지 (R3 / Codex F8) | **신규 Lua** |
| **Redisson group** | `RedissonLeaderGroupElector` 내부 `RPermitExpirableSemaphore` | `fun extendPermit(permitId, leaseTime): ExtendOutcome` | 기존 `updateLeaseTime(permitId, ms, MS)` — Redisson 내부 atomic | 기존 (#151) |
| **MongoDB single sync** | `leader-mongodb/.../lock/MongoLock.kt` | `fun extend(leaseTime: Duration): ExtendOutcome` | **filter 강화** `{_id, token, expireAt: { $gt: now } }` (R6 / Codex F6) — atomic + 만료 부활 차단 | 기존 수정 |
| **MongoDB single suspend** | `MongoSuspendLock.kt` | `suspend fun extend(...): ExtendOutcome` | 동일 + `withContext(IO)` | 기존 수정 |
| **MongoDB group** | `MongoLeaderGroupElector` / `MongoSuspendLeaderGroupElector` | `fun extendSlot(slotId, leaseTime): ExtendOutcome` | per-slot doc filter `{name, slotId, token, expireAt: { $gt: now } }` | **신규** (Codex F12) |
| **Exposed JDBC single** | `leader-exposed-jdbc/.../lock/ExposedJdbcLock.kt` | `fun extend(...): ExtendOutcome` | `UPDATE leader_lock SET expire_at = now + interval WHERE name=? AND token=? AND expire_at > now()` (transient `SQLException` → `BackendError`, non-transient → propagate) | **신규** |
| **Exposed JDBC group** | `ExposedJdbcGroupLock.kt` | `fun extendSlot(slotToken, leaseTime): ExtendOutcome` | per-slot row 동일 SQL | **신규** |
| **Exposed R2DBC single** | `leader-exposed-r2dbc/.../lock/ExposedR2dbcLock.kt` | `suspend fun extend(...): ExtendOutcome` | 동일 SQL, R2DBC suspend transaction + `ensureActive()` | **신규** |
| **Exposed R2DBC group** | `ExposedR2dbcGroupLock.kt` | `suspend fun extendSlot(...): ExtendOutcome` | 동일 | **신규** |
| **Hazelcast single** | `leader-hazelcast/.../lock/HazelcastLock.kt` | `fun extend(...): ExtendOutcome` | `IMap.executeOnKey(lockKey, ExtendEntryProcessor(token, lease))` — EntryProcessor 내부 `entry.value == ours && expireAt > now → setValue(token, lease, MS) ELSE NotHeld` (R2). plain `setTtl` 금지 | **신규** |
| **Hazelcast group / suspend** | `HazelcastSuspendLock.kt` 등 | 동일 + `withContext(IO)` | 동일 | **신규** |
| **ZooKeeper** | `leader-zookeeper/.../ZkLeaderElector.kt` 내부 `InterProcessMutex` | `fun extend(...): ExtendOutcome` | **session live passthrough** (R3-F11) — `if (mutex.isAcquiredInThisProcess()) Extended(observedExpireAt = Instant.MAX) else NotHeld`. `Extended` 는 "lease 연장" 이 아닌 "session-held liveness 확인" 의미. group semaphore 도 lease TTL 없음 — 동일 passthrough. **`LeaderLeaseAutoExtender.start(enabled=false)` 강제** (R16) | **신규** passthrough |
| **Local single** | `leader-core/.../local/LocalLeaderStateRegistry.kt` | `fun extend(name, token, leaseTime): ExtendOutcome` | `ConcurrentHashMap.compute` atomic + `expireAt > now` guard | **신규** |
| **Local group** | `LocalLeaderGroupRegistry` (가칭) | `fun extendSlot(name, slot, leaseTime): ExtendOutcome` | 동일 | **신규** |

### Backend exception policy (SF9 + R4-F5)

**SPI 패턴 — backend module 의존성 역전** (R5-F4): `leader-core` 는 backend exception class 직접 참조 불가 (build dependency 문제). SPI interface + JDK/common 분류만 core 에 두고, 각 backend module 이 자기 classifier 등록.

```kotlin
// leader-core/src/main/kotlin/io/bluetape4k/leader/internal/BackendErrorClassifier.kt
package io.bluetape4k.leader.internal

enum class BackendErrorKind { TRANSIENT, NON_TRANSIENT, FATAL }

/** SPI — 각 backend module 이 자기 backend 용 구현 등록. */
fun interface BackendErrorClassifier {
    fun classify(cause: Throwable): BackendErrorKind?  // null = 분류 불가, chain next
}

/** JDK / 공통 분류 — backend dependency 없음 (R5-F4). */
internal object CoreBackendErrorClassifier : BackendErrorClassifier {
    override fun classify(cause: Throwable): BackendErrorKind = when (cause) {
        is OutOfMemoryError, is StackOverflowError, is LinkageError -> BackendErrorKind.FATAL
        is java.sql.SQLTransientException -> BackendErrorKind.TRANSIENT
        is java.sql.SQLRecoverableException -> BackendErrorKind.TRANSIENT
        is java.sql.SQLNonTransientException -> BackendErrorKind.NON_TRANSIENT
        is java.net.SocketTimeoutException -> BackendErrorKind.TRANSIENT
        is java.net.ConnectException -> BackendErrorKind.TRANSIENT
        else -> BackendErrorKind.NON_TRANSIENT  // safe default
    }
}

/** classifier chain — 각 elector 가 (자기 backend classifier + Core) 합성 호출. */
internal class CompositeBackendErrorClassifier(
    private val backendSpecific: BackendErrorClassifier,
) : BackendErrorClassifier {
    override fun classify(cause: Throwable): BackendErrorKind =
        backendSpecific.classify(cause) ?: CoreBackendErrorClassifier.classify(cause) ?: BackendErrorKind.NON_TRANSIENT
}
```

각 backend module 의 classifier (예시):

```kotlin
// leader-redis-lettuce/.../internal/LettuceBackendErrorClassifier.kt
internal object LettuceBackendErrorClassifier : BackendErrorClassifier {
    override fun classify(cause: Throwable): BackendErrorKind? = when (cause) {
        is io.lettuce.core.RedisCommandTimeoutException -> BackendErrorKind.TRANSIENT
        is io.lettuce.core.RedisConnectionException -> BackendErrorKind.TRANSIENT
        is io.lettuce.core.RedisCommandExecutionException -> BackendErrorKind.NON_TRANSIENT
        else -> null  // Core classifier 에 위임
    }
}
// leader-redis-redisson/.../RedissonBackendErrorClassifier.kt
// leader-mongodb/.../MongoBackendErrorClassifier.kt
// leader-exposed-r2dbc/.../R2dbcBackendErrorClassifier.kt  (R2dbcTransientException 등)
// leader-hazelcast/.../HazelcastBackendErrorClassifier.kt
// leader-zookeeper/.../ZkBackendErrorClassifier.kt
```

각 elector 가 `CompositeBackendErrorClassifier(LettuceBackendErrorClassifier)` 형태로 합성 — `leader-core` build 깨지지 않음.

- **Transient** → `ExtendOutcome.BackendError(cause)` 반환 + WARN log + metric.
- **Non-transient** → throw — caller 가 결정.
- **FATAL** → propagate (catch 안 함).
- **`CancellationException`** → 항상 re-throw (suspend backend 의 R9 mitigation).

### Watchdog interaction

- 모든 backend 의 atomic extend 메서드는 watchdog 와 **동일 `ExtendDelegate` 객체** 공유 (Architect A3 / R5 / R3-F6):

```kotlin
// 각 elector 내부 — runIfLeader acquire 직후
val extendDelegate: ExtendDelegate = object : ExtendDelegate {
    override fun extend(d: Duration): ExtendOutcome = lock.extend(d)
    override suspend fun extendSuspend(d: Duration): ExtendOutcome = lock.extendSuspend(d)  // backend 가 suspend native 면 native 호출
    override fun isHeld(): Boolean = lock.isHeldByCurrentInstance()
}
LeaderLeaseAutoExtender.start(enabled = options.autoExtend, leaseTime = options.leaseTime, delegate = extendDelegate)
val handle = LeaderLockHandle.Real(..., extendDelegate = extendDelegate, ...)
```

- `LeaderLeaseAutoExtender.start` 시그니처 변경 (`(Duration) -> Boolean` 람다 → `delegate: ExtendDelegate`).
- AC-15: `handle.extendDelegate === watchdog.delegate` reference 동일 (test verify).

---

## 7. AOP 분기

### 7.1 Sync 분기 (`aroundLeader`)

```kotlin
private fun aroundLeader(pjp: ProceedingJoinPoint, meta: AdviceMetadata): Any? {
    val identity = meta.resolveLockIdentity(AdviceBranch.SYNC)  // R5-F6 — sync branch

    // 1) Reentrant peek — full identity (Codex F3)
    val outer = LockStateHolder.peekSync()
    if (outer is LeaderLockHandle.Real && outer.matchesIdentity(identity)) {
        return LockStateHolder.withPushed(outer.withReentryDepth(outer.reentryDepth + 1)) { pjp.proceed() }
    }

    // 2) 정상 경로 — body / capture-invariant / backend exception 명확히 분리 (R4-F2 + R5-F2 + R5-F3)
    val elector = ... // 기존 캐시 로직

    /** ⭐ R5-F2 — body 실행을 BodyThrownMarker 로 보호하는 helper. elected/fail-open 양쪽에 사용. */
    fun proceedProtected(): Any? = try {
        pjp.proceed()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        throw BodyThrownMarker(e)
    }

    return try {
        when (val result = elector.runIfLeaderResult(identity.lockName) {
            // ⭐ capture 누락 = elector 구현 버그 — CaptureInvariantException 직접 throw (R5-F3).
            //   일반 IllegalStateException 과 구분 (backend 도 ISE throw 가능).
            val handle = LeaderLockHandleCapture.poll()
                ?: throw CaptureInvariantException("elector did not capture handle — bug in ${elector::class.simpleName}")
            LockStateHolder.withPushed(handle) { proceedProtected() }
        }) {
            is LeaderRunResult.Elected -> result.value
            is LeaderRunResult.Skipped -> when (meta.failureMode) {
                FAIL_OPEN_RUN -> LockStateHolder.withPushed(LeaderLockHandle.failOpen(identity)) {
                    proceedProtected()  // ⭐ R5-F2 — fail-open body 도 BodyThrownMarker 보호
                }
                SKIP, RETHROW -> null   // ⭐ R3-F1 — contention 정상 흐름.
                INHERIT -> error("INHERIT must be resolved in resolveMetadata")
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: BodyThrownMarker) {
        throw e.cause!!   // body exception 그대로 propagate — failureMode 무관
    } catch (e: CaptureInvariantException) {
        throw e            // ⭐ R5-F3 — 전용 exception type (일반 ISE 와 구분)
    } catch (e: Exception) {  // ⭐ backend exception 만 (R3-F5 — Throwable 아님)
        when (meta.failureMode) {
            FAIL_OPEN_RUN -> LockStateHolder.withPushed(LeaderLockHandle.failOpen(identity)) {
                proceedProtected()  // ⭐ R5-F2 — backend fail-open body 도 보호
            }
            SKIP -> null  // 침묵 + WARN log + metric
            RETHROW -> throw e
            INHERIT -> error("INHERIT must be resolved in resolveMetadata")
        }
    }
}

/** body exception marker (R4-F2) */
private class BodyThrownMarker(cause: Throwable) : RuntimeException(cause)

/** capture invariant 실패 전용 exception — 일반 IllegalStateException 과 구분 (R5-F3) */
internal class CaptureInvariantException(message: String) : IllegalStateException(message)
```

### 7.2 Suspend 분기 (`aroundLeaderSuspend`)

```kotlin
private suspend fun aroundLeaderSuspendBody(pjp: ProceedingJoinPoint, meta: AdviceMetadata): Any? {
    val identity = meta.resolveLockIdentity(AdviceBranch.SUSPEND)  // R5-F6 — suspend branch (Mono 분기는 AdviceBranch.MONO)

    // 1) Reentrant peek — coroutineContext only (R7)
    val outer = currentCoroutineContext()[LockHandleElement]?.handle as? LeaderLockHandle.Real
    if (outer != null && outer.matchesIdentity(identity)) {
        // ⭐ R3-F4 — LeaderElectionInfo + LockHandleElement 결합 push (기존 aspect 가 LeaderElectionInfo 주입)
        val passthrough = outer.withReentryDepth(outer.reentryDepth + 1)
        return withContext(LeaderElectionInfo(identity.lockName, true) + LockHandleElement(passthrough)) {
            suspendBlock.startCoroutineUninterceptedOrReturn(...)
        }
    }

    // 2) 정상 경로 — body / capture-invariant / backend exception 분리 (R4-F2 + R5-F2 + R5-F3)
    suspend fun proceedProtected(): Any? = try {
        suspendBlock.startCoroutineUninterceptedOrReturn(...)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        throw BodyThrownMarker(e)
    }

    return try {
        when (val result = suspendElector.runIfLeaderResultSuspend(identity.lockName) {
            val handle = LeaderLockHandleCapture.poll()
                ?: throw CaptureInvariantException("suspend elector did not capture handle — bug in ${suspendElector::class.simpleName}")
            withContext(LeaderElectionInfo(identity.lockName, true) + LockHandleElement(handle)) {
                proceedProtected()
            }
        }) {
            is LeaderRunResult.Elected -> result.value
            is LeaderRunResult.Skipped -> when (meta.failureMode) {
                FAIL_OPEN_RUN -> withContext(LeaderElectionInfo(identity.lockName, false) + LockHandleElement(LeaderLockHandle.failOpen(identity))) {
                    proceedProtected()  // ⭐ R5-F2 — contention fail-open body 도 보호
                }
                SKIP, RETHROW -> null
                INHERIT -> error("INHERIT must be resolved")
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: BodyThrownMarker) {
        throw e.cause!!
    } catch (e: CaptureInvariantException) {
        throw e
    } catch (e: Exception) {  // ⭐ backend exception 만 (R3-F5)
        when (meta.failureMode) {
            FAIL_OPEN_RUN -> withContext(LeaderElectionInfo(identity.lockName, false) + LockHandleElement(LeaderLockHandle.failOpen(identity))) {
                proceedProtected()  // ⭐ R5-F2 — backend fail-open body 도 보호
            }
            SKIP -> null
            RETHROW -> throw e
            INHERIT -> error("INHERIT must be resolved")
        }
    }
}
```

### 7.3 Mono 분기 (`aroundLeaderMono`)

`Mono.defer { mono { aroundLeaderSuspendBody(...) } }` — suspend 분기 본문 재사용. **`mono { withContext(...) }` 안에서만 propagation 보장**. 임의 Reactor operator (`subscribeOn`, `publishOn`) 는 미보장 (Codex F14) — KDoc 명시.

### 7.4 Reentrant 의미론

- **동일 lock identity (lockName + kind + factoryBean)**: passthrough — backend 미접촉.
- **다른 identity (lockName 같지만 kind 다름)**: 정상 acquire (Codex F3).
- **Group nested**: outer 의 `slotId` 재사용 — backend 미접촉 (R13 / Architect A5).
- **Sync ↔ Suspend cross-context**: 미지원 — sync ThreadLocal 과 suspend coroutineContext 간 fallback 없음 (R7). 사용자가 sync 안에서 `runBlocking` 등으로 suspend 호출 시 inner 는 새 acquire 시도.

### 7.5 Validator 강화 (R12 / Codex F9)

`LeaderAnnotationValidatorBeanPostProcessor` 에 추가:
- `method.returnType ∈ {CompletableFuture, Future, ListenableFuture}` → strict throw / non-strict WARN.
- 메시지: "@LeaderElection on Future/CompletableFuture-returning method is unsupported in v1 — lock would release before future completes."

### 7.5b AdviceMetadata 변경 (R4-F3)

```kotlin
// leader-spring-boot/.../aop/LeaderElectionAspect.kt — 의사코드
internal data class AdviceMetadata(
    val lockName: String,
    val failureMode: LeaderAspectFailureMode,
    val syncFactoryBeanName: String?,           // sync branch 용
    val suspendFactoryBeanName: String?,        // suspend / Mono branch 용
    val groupParams: LockIdentity.GroupParams?, // group annotation 일 때만 non-null
    // ... 기존 필드
) {
    /** branch 에 맞는 factoryBeanName 으로 LockIdentity 생성. */
    fun resolveLockIdentity(branch: AdviceBranch): LockIdentity = LockIdentity(
        lockName = lockName,
        kind = if (groupParams != null) AnnotationKind.GROUP else AnnotationKind.SINGLE,
        factoryBeanName = when (branch) {
            AdviceBranch.SYNC -> requireNotNull(syncFactoryBeanName)
            AdviceBranch.SUSPEND, AdviceBranch.MONO -> requireNotNull(suspendFactoryBeanName)
        },
        groupParams = groupParams,
    )
}

internal enum class AdviceBranch { SYNC, SUSPEND, MONO }
```

`LeaderElectionAspect.aroundLeader` (sync) → `meta.resolveLockIdentity(SYNC)`, `aroundLeaderSuspend` → `SUSPEND`, `aroundLeaderMono` → `MONO`. 동일 lockName 이라도 sync/suspend factory bean 이 다르면 별개 identity → reentrant peek 시 정확한 dedupe.

### 7.6 Elector 변경 사항

각 backend elector 의 **기존 public default fun** `runIfLeaderResult(lockName, action): LeaderRunResult<T>` 를 그대로 사용 (sync). suspend backend 는 본 PR 에서 추가하는 `runIfLeaderResultSuspend(lockName, action)` default fun 호출 (R4-F1).

acquire 직후 `LeaderLockHandleCapture.set(handle)` 호출 — **action lambda 호출 직전, 동일 thread**. action 종료 후 `clear()` (try/finally).

영향받는 elector 파일 (Codex F3, A1 검증 완료):
- Local: `LocalLeaderElector`, `LocalSuspendLeaderElector`, `LocalLeaderGroupElector`, `LocalSuspendLeaderGroupElector`, `LocalVirtualThreadLeaderElector`, `LocalVirtualThreadLeaderGroupElector`, `LocalAsyncLeaderElector`, `LocalAsyncLeaderGroupElector`
- Lettuce: `LettuceLeaderElector`, `LettuceSuspendLeaderElector`, `LettuceLeaderGroupElector`, `LettuceSuspendLeaderGroupElector`
- Redisson: `RedissonLeaderElector`, `RedissonSuspendLeaderElector`, `RedissonLeaderGroupElector`, `RedissonSuspendLeaderGroupElector`
- MongoDB: `MongoLeaderElector`, `MongoSuspendLeaderElector`, `MongoLeaderGroupElector`, `MongoSuspendLeaderGroupElector`
- Exposed JDBC/R2DBC: `ExposedJdbcLeaderElector`, `ExposedR2dbcSuspendLeaderElector`, group variants
- Hazelcast: `HazelcastLeaderElector`, `HazelcastSuspendLeaderElector`, group variants
- ZK: `ZkLeaderElector`, `ZkSuspendLeaderElector`, group variants

---

## 8. Test Strategy

### 8.1 4 Abstract Contract Bases (Architect A6 / Codex F15)

```kotlin
abstract class AbstractSyncLockExtenderContractTest {
    abstract val elector: LeaderElector
    abstract fun probeBackendExpireAt(lockName: String): Instant?
    @Test fun `assertLocked passes inside annotated body`() { ... }
    @Test fun `assertLocked throws outside body`() { ... }
    @Test fun `extend returns true when held — TTL updated`() { ... }
    @Test fun `extend returns false after release`() { ... }
    @Test fun `extend returns false after lease expiry (takeover)`() { ... }
    @Test fun `extend returns false after token mismatch (other node took over)`() { ... }
    @Test fun `concurrent extends are race-free, last-write-wins`() { ... }
    @Test fun `extendActiveLockDetailed returns BackendError on transient failure`() { ... }
    @Test fun `assertLocked with mismatched lockName throws`() { ... }
}
abstract class AbstractSuspendLockExtenderContractTest { ... + cancellation tests }
abstract class AbstractGroupLockExtenderContractTest { ... + slot identity }
abstract class AbstractSuspendGroupLockExtenderContractTest { ... }
```

**Backend × Contract Capability Matrix** (R2-F9):

| Backend | sync | suspend | group | suspend-group | 비고 |
|---|:-:|:-:|:-:|:-:|---|
| Local | ✅ | ✅ | ✅ | ✅ | virtual-thread / async 도 sync base 재사용 |
| Lettuce | ✅ | ✅ | ✅ | ✅ | |
| Redisson | ✅ | ✅ | ✅ | ✅ | sync base 에 cross-thread test 추가 |
| MongoDB | ✅ | ✅ | ✅ | ✅ | suspend native — sync 는 `runBlocking` wrap |
| Exposed JDBC | ✅ | — | ✅ | — | R2DBC 와 분리 — sync 만 |
| Exposed R2DBC | — | ✅ | — | ✅ | suspend 만 |
| Hazelcast | ✅ | ✅ | ✅ | ✅ | |
| ZooKeeper | ✅ | ✅ | ✅ | ✅ | extend = passthrough; AC-20 watchdog noop |

unsupported 행은 concrete test class 부재 (skip 아님). matrix 자체가 AC-1 검증 단위.

각 backend 모듈에 해당 capability 의 concrete 테스트 서브클래스 추가:
- `LettuceLockExtenderContractTest` (sync), `LettuceSuspendLockExtenderContractTest`, `LettuceLockExtenderGroupContractTest`, `LettuceSuspendGroupLockExtenderContractTest`
- 기타 backend 도 동일 패턴 (matrix 의 ✅ 행만)

### 8.2 Cross-cutting integration tests (`leader-spring-boot`)

| Scenario | 검증 |
|---|---|
| `LockAssert.assertLocked()` × `@LeaderElection` × 8 backend × sync/suspend/Mono | passes |
| Reentrant `@LeaderElection` same identity, same thread | inner acquire counter 증가 0 (mockk verify) |
| Reentrant cross-elector 동일 lockName 다른 factoryBean | 정상 acquire (passthrough 안 함) — Codex F3 |
| `@LeaderElection` ↔ `@LeaderGroupElection` 동일 lockName nested | 둘 다 정상 acquire (kind 다름) |
| Group nested same lockName | inner slotId 재사용, acquire 0 |
| FAIL_OPEN_RUN — contention 분기 | sentinel push, `assertLocked` throw, `extend` `false` |
| FAIL_OPEN_RUN — backend exception 분기 | 동일 (Architect A2) |
| Suspend `assertLockedSuspend` ThreadLocal fallback 제거 검증 | sync ThreadLocal push 후 suspend `assertLockedSuspend` 호출 → IllegalStateException |
| Watchdog 활성 + `extendActiveLock(60s)` (lease=30s) | WARN log 1회 + metric `lock_extender_overridden_total` 증가 |
| Redisson cross-carrier-thread extend | `WrongThread` outcome + WARN |
| Hazelcast EntryProcessor — stale token | `NotHeld` outcome (lost-update 방지) |
| ZK extend — session live | `Extended(Instant.MAX)` |
| ZK + autoExtend=true | startup WARN + watchdog noop |
| `@LeaderElection` on `CompletableFuture` 반환 메서드 | strict=true → IllegalStateException at startup, strict=false → WARN |
| Mono propagation: arbitrary Reactor `publishOn` 후 `assertLockedSuspend` | undefined — 미지원 명시 (Codex F14) |
| `LockExtender.extendActiveLock(d)` cancellation | `CancellationException` re-throw — `runCatching` 사용 0회 (grep verify) |

### 8.3 테스트 도구 준수
- JUnit 5 + MockK + bluetape4k-assertions, `@TestInstance(PER_CLASS)`, suspend → `runTest`, 예외 → `assertFailsWith<T> { }`, Testcontainers singleton.

---

## 9. Acceptance Criteria

- [ ] **AC-1**: §8.1 capability matrix 의 ✅ 모든 cell 에 concrete contract test 통과 (R3-F10 — ZK group/suspend-group 실제 존재; R2DBC 는 sync 미지원만 unsupported).
- [ ] **AC-2**: `@LeaderElection` 동일 full-identity reentrant 호출 시 backend acquire counter 정확히 1회 (mockk verify).
- [ ] **AC-2b**: `@LeaderElection` ↔ `@LeaderGroupElection` 동일 lockName 은 dedupe 안 됨 — 둘 다 정상 acquire (Codex F3).
- [ ] **AC-3**: `LockAssert.assertLocked()` annotated 외부 호출 시 IllegalStateException (메시지에 "outside" / "no active scope" 포함).
- [ ] **AC-4**: `LockExtender.extendActiveLock(d)` 가 fail-open sentinel scope 에서 `false` + WARN 로그 + metric (rate-limited).
- [ ] **AC-4b**: failure mode × 분기 행렬 검증 (Architect A2 / R2-F1 / R3-F1):

| 분기 | `RETHROW` | `SKIP` | `FAIL_OPEN_RUN` |
|---|---|---|---|
| `Elected` | body 실행 + lock | body 실행 + lock | body 실행 + lock |
| `Skipped` (contention) | `null` | `null` | sentinel push + body 실행 |
| `Exception` (backend) | throw | `null` + WARN | sentinel push + body 실행 |

  - `Skipped × RETHROW` 는 **`null` 반환** (contention 은 정상 흐름, `runIfLeader` contract).
  - `Exception × RETHROW` 만 실제 throw.
  - sentinel push 는 `FAIL_OPEN_RUN` 분기에서만 (Skipped + Exception 모두).
  - `catch` 범위는 `Exception` — `OutOfMemoryError`/`StackOverflowError`/`LinkageError` 등은 제외 (R3-F5).
- [ ] **AC-5**: Sync, suspend, Mono 3 분기 모두 `LockAssert` / `LockExtender` 동작 (각 분기 × 2 API = 6).
- [ ] **AC-6**: Watchdog 활성 + `extendActiveLock` 동시 호출 race-free — torn write 0 (TTL 가 두 마지막 호출의 최댓값 또는 그 사이 값, 단조 가정 X).
- [ ] **AC-6b**: watchdog cadence 다음 tick 에서 user-extended 큰 값이 watchdog 작은 값으로 silently 줄어들 때 WARN log + metric (R5 / SF5).
- [ ] **AC-7**: Hazelcast extend 는 EntryProcessor 사용 — plain `setTtl` 호출 0회 (소스 grep + Mockk verify).
- [ ] **AC-8**: Redisson extend thread-id semantics (R2-F11):
  - `acquiringThreadId` = `Thread.currentThread().threadId()` at acquire time
  - `extend(d)` 시 `Thread.currentThread().threadId() != acquiringThreadId` → `WrongThread` outcome + WARN
  - 시나리오 검증: (a) virtual thread carrier hop, (b) `Dispatchers.IO` hop, (c) `mono { withContext(IO) }` 모두에서 thread-id mismatch 시 WrongThread
  - KDoc: "Redisson `RLock` 은 acquire 한 platform/virtual thread 와 동일 thread 에서만 explicit extend 가능. dispatcher hop 시 WrongThread 가능"
- [ ] **AC-9**: README + README.ko.md (`leader-spring-boot`) — Mermaid sequenceDiagram 포함 reentrant 시나리오.
- [ ] **AC-10**: `LockAssert`, `LockExtender`, `LeaderLockHandle.Real`, `LeaderLockHandle.FailOpen`, `ExtendOutcome` 의 모든 public 멤버 KDoc + `## 동작/계약` + `kotlin` 예제.
- [ ] **AC-11**: `./gradlew detekt` 통과 — 신규 HIGH/CRITICAL 0건.
- [ ] **AC-12**: Kover coverage 신규 코드 80%+.
- [ ] **AC-13**: `./gradlew build -x test` 로컬 빌드 통과.
- [ ] **AC-14**: `LeaderAnnotationValidatorBeanPostProcessor` — `CompletableFuture` 반환 메서드 strict=true → throw, strict=false → WARN (Codex F9).
- [ ] **AC-15**: 각 backend elector 가 동일 `ExtendDelegate` 객체를 watchdog (`LeaderLeaseAutoExtender.start(delegate=...)`) 와 `LeaderLockHandle.Real.extendDelegate` 양쪽에 전달 — `handle.extendDelegate === watchdog.delegate` 검증 (test verify) (R5 / Architect A3 / R2-F4).
- [ ] **AC-16**: Lettuce group extend Lua — `redis.call('TIME')` 사용 검증 (소스 grep) (R14).
- [ ] **AC-17**: Mongo extend filter `expireAt: { $gt: now }` 포함 검증 (소스 inspect) (R6 / Codex F6).
- [ ] **AC-18**: suspend extend 구현에서 `CancellationException` first-class re-throw 검증 (R2-F10):
  - grep 패턴: `runCatching|Result\.runCatching|catch\s*\(\s*(Throwable|Exception)\s*[):]`
  - 매칭된 모든 위치에 `catch(CancellationException) { throw e }` 가 선행하는지 inspect
  - helper 함수 안에 swallow 된 케이스도 점검
- [ ] **AC-19**: Java caller 가 `LockExtender.extendActiveLock(java.time.Duration)` 호출 가능 — `LockExtenderJavaCompatTest` (R15).
- [ ] **AC-20**: ZK + autoExtend=true 시 startup WARN — watchdog noop (R16).
- [ ] **AC-21**: Blocking backend 의 `ExtendDelegate.extendSuspend` 가 default 사용 0회 (R3-F8 / R4-F7):
  - 검증: 각 blocking backend 모듈의 `ExtendDelegate` 익명 객체 정의에 `override suspend fun extendSuspend` 가 명시적으로 있고 `withContext(Dispatchers.IO)` + `coroutineContext.ensureActive()` 호출
  - 자동 검증 명령:
    ```bash
    # 1. ExtendDelegate 익명 객체 위치
    rg -n "object\s*:\s*ExtendDelegate" leader-redis-lettuce leader-redis-redisson leader-mongodb leader-exposed-jdbc leader-hazelcast leader-zookeeper
    # 2. 각 위치에 override suspend extendSuspend 존재 확인
    rg -n "override\s+suspend\s+fun\s+extendSuspend" leader-redis-lettuce leader-redis-redisson leader-mongodb leader-exposed-jdbc leader-hazelcast leader-zookeeper
    # 3. withContext(Dispatchers.IO) 사용 확인
    rg -n "withContext\(Dispatchers\.IO\)" leader-redis-lettuce leader-redis-redisson leader-mongodb leader-exposed-jdbc leader-hazelcast leader-zookeeper
    ```
  - R2DBC / Local 은 native suspend → default OK (Local 은 non-blocking, R2DBC 는 suspend native)
- [ ] **AC-22**: AOP CTW weave smoke test — sync/suspend/Mono 각각 실제 woven bean 호출로 `LockHandleElement` propagation 검증 (R3-F13).
- [ ] **AC-23**: `handle.extendDelegate === watchdog.delegate` reference 검증 — 각 backend elector 모듈 안에 unit test (`leader-redis-lettuce`, `leader-redis-redisson`, ...) — `extendDelegate` 가 `internal` 이라 cross-module access 불가 (R3-F14).
- [ ] **AC-24**: SPI 분산 backend classifier 검증 (R3-F9 / R5-F4):
  - `leader-core` 의 `BackendErrorClassifier` SPI + `CoreBackendErrorClassifier` (JDK/공통) + `CompositeBackendErrorClassifier`
  - 각 backend module 의 자기 classifier 구현 + 단위 테스트 (known exception 모두 cover)
  - **`leader-core` build 가 backend exception class 직접 참조 0회** (소스 grep verify)
- [ ] **AC-25**: Kotlin interface default fun binary-compat 검증 (R5-F5):
  - `-jvm-default=enable` 으로 default fun 이 JVM `default` method 로 컴파일됨 확인 (`javap -p` inspection)
  - 기존 구현체 (LettuceLeaderElector 등) 가 `runIfLeaderResultSuspend` 를 override 하지 않아도 compile/runtime OK 검증

---

## 10. Migration / Compatibility

### 10.1 Source-compat
- `SuspendLeaderElector` / `SuspendLeaderGroupElector` 에 `runIfLeaderResultSuspend` default fun 추가 — **source-compatible**.
- `LeaderElector` / `LeaderGroupElector` / `AsyncLeaderElector` / `VirtualThreadLeaderElector` 무변경.

### 10.1b Binary-compat 주의 (R5-F5)
- 본 repo 는 `-jvm-default=enable` 사용 — Kotlin interface default fun 이 JVM `default` method 로 컴파일.
- 외부에서 컴파일된 기존 구현체 (Java 또는 Kotlin) 는 default 메서드 가용 시 자동 사용 — **binary 호환 가능성 높음**.
- 다만 `-jvm-default` 전략 변경 시 `AbstractMethodError` 위험 → **AC-25 추가 — generated bytecode 확인 또는 외부 구현체 reflection 호환 테스트**.
- 본 PR 에서 backend 별 elector 구현체는 모두 동일 module 내 컴파일 → caller-side 호환만 검증하면 충분.
- `LeaderElectionInfo` 무변경 — 별도 `LockHandleElement` 추가 (R8 / Type T8).
- `LockAssert`, `LockExtender`, `LeaderLockHandle`, `ExtendOutcome` 모두 신규 — 추가 only.

### 10.2 Binary-compat
- `LeaderElectionInfo` data class 무변경 — `componentN()` / `copy()` ABI 유지.
- `MongoLock.token` private → internal — 같은 모듈 내부만 영향.
- `LeaderLockHandle` 는 신규 sealed class — 외부 영향 없음.

### 10.3 v0.x → v1.x 마이그레이션 (Architect A10)
- AOP 미사용자: 영향 없음 (top-level objects 추가만).
- AOP 사용자: 자동 활성. `@LeaderElection` 동작 유지 + 추가로 `LockAssert`/`LockExtender` 사용 가능.
- Watchdog ON + LockExtender 사용: last-write-wins 권고 — watchdog OFF 또는 user lease 보호 patch (R5).

### 10.4 ShedLock 사용자 마이그레이션
```java
// ShedLock (2-arg)
LockExtender.extendActiveLock(Duration.ofMinutes(5), Duration.ofSeconds(10));

// bluetape4k-leader (1-arg, absolute)
LockExtender.extendActiveLock(Duration.ofMinutes(5));
// ⚠️ Semantics differ: ShedLock 의 `lockAtLeastFor` 는 bluetape4k 의
//   LeaderElectionOptions.minLeaseTime (별도 backend mechanism, #108) 로 대체.
```

---

## 11. Documentation Plan

- **`leader-spring-boot/README.md` + `README.ko.md`** — 신규 섹션 "LockAssert & LockExtender (ShedLock-equivalent)" + Mermaid sequenceDiagram.
- 8 backend 모듈 README — "extend now supported" 한 줄 노트.
- **`leader-ktor/README.md`** — 한 줄 노트: "plugin 안에서 `LockAssert.assertLockedSuspend()` 사용 가능" (Architect A8).
- KDoc — 모든 신규 public surface + `ExtendOutcome` variants.
- **CHANGELOG.md** — `LockHandleElement` 추가, `LockAssert`/`LockExtender` API 신규, validator `CompletableFuture` 차단, watchdog × extend 가시성 (Architect A9).
- **`WIP.md`** — Issue #79 완료 표시.
- **CLAUDE.md (root + leader-bom)** — "v1.x sync-only" 문구 → "suspend + Mono 지원, Flux/Flow 미지원" 으로 갱신 (Architect A11).
- **`docs/lessons/2026-05-10-lock-extender.md`** — PR merge 후: Hazelcast EntryProcessor 함정, Redisson owner-Lua, capture invariant, sealed `ExtendOutcome` 도입, ShedLock semantics divergence.

---

## 12. Draft Task List

| ID | Task | Complexity | Depends |
|---|---|---|---|
| **T1** | `LeaderLockHandle` sealed class (Real / FailOpen) + internal constructor + `ExtendOutcome` sealed result + `LockStateHolder` + `LeaderLockHandleCapture` (`leader-core`) | medium | — |
| **T2** | `LockHandleElement` (`CoroutineContext.Element`) 신규 — `LeaderElectionInfo` 무변경 (`leader-core`) | low | T1 |
| **T3** | `SuspendLeaderElector` / `SuspendLeaderGroupElector` 에 `runIfLeaderResultSuspend` default fun 추가 (`LeaderRunResult` 무변경) (`leader-core`) | low | T1 |
| **T4** | `LockAssert` / `LockExtender` top-level objects + Java `java.time.Duration` overload + `extendActiveLockDetailed` + KDoc (`leader-core`) | medium | T1, T2 |
| **T5** | Local elector — capture + `runIfLeaderResult` internal + `LocalLeaderStateRegistry.extend` (`leader-core`) | medium | T1, T3 |
| **T6** | 4 abstract contract bases (sync/suspend/group/suspend-group) + Local 4 concrete tests (`leader-core`) | medium | T4, T5 |
| **T7** | Lettuce — single sync/suspend `extend` 반환형 변경 + group `extendSlot` Lua (server-side TIME) + capture + `runIfLeaderResult` (`leader-redis-lettuce`) | high | T1, T3 |
| **T8** | Redisson — owner-guarded Lua single + group `updateLeaseTime` + thread-id 가드 + capture (`leader-redis-redisson`) | high | T1, T3 |
| **T9** | MongoDB — extend filter 강화 (`expireAt > now`) + group `extendSlot` 신규 + capture (`leader-mongodb`) | medium | T1, T3 |
| **T10** | Exposed JDBC — `extend` SQL + group `extendSlot` SQL + capture (`leader-exposed-jdbc`) | medium | T1, T3 |
| **T11** | Exposed R2DBC — suspend SQL + capture (`leader-exposed-r2dbc`) | medium | T10 |
| **T12** | Hazelcast — `ExtendEntryProcessor` 신규 + capture (`leader-hazelcast`) | high | T1, T3 |
| **T13** | ZooKeeper — passthrough + autoExtend=false 강제 + capture (`leader-zookeeper`) | low | T1, T3 |
| **T14** | `LeaderElectionAspect` — sync/suspend/Mono 3 분기 + reentrant peek (full identity) + sentinel push (contention + backend exception) + `runIfLeaderResult` 사용 (`leader-spring-boot`) | high | T1, T2, T3 |
| **T15** | `LeaderGroupElectionAspect` — group 분기 동일 패턴 (`leader-spring-boot`) | high | T14 |
| **T16** | `LeaderAnnotationValidatorBeanPostProcessor` — `CompletableFuture` 반환 fail-fast (`leader-spring-boot`) | medium | — |
| **T17** | 통합 테스트 — 모든 시나리오 (`leader-spring-boot` + 각 backend 모듈 contract) | high | T7–T16 |
| **T18** | README (`leader-spring-boot/README.md` + `.ko.md`) + Mermaid sequenceDiagram + 8 backend README 노트 + `leader-ktor` 노트 | medium | T17 |
| **T19** | CHANGELOG.md + WIP.md 업데이트 + 두 CLAUDE.md drift 수정 (root + leader-bom) | low | T18 |

**Critical path**: T1 → T4 → T14 → T17 → T18 → T19. T7–T13 backend 별 병렬 가능 (T1 완료 후).

---

## Appendix A — Round 1 Review 통합 (Codex + 4 perspective)

총 63 finding (P0×2, P1×30, P2×13, P3×0, low ~12, low-priority/observational ×6).

### 적용된 P0/P1/HIGH (32개 → spec 본문 반영)

- **Codex F1** (P0) → §0, §5.7 `LeaderRunResult`, §7.1/§7.2
- **Codex F2** (P0) → §3 R9, §5.2 `suspendExtendFn`, §6 표
- **Codex F3** (P1) → §3 R1, §4.2, §7.1, AC-2/2b
- **Codex F4** (P1) → §3 R15, §5.1 Java overload, AC-19
- **Codex F5 / Type T8** (P1/HIGH) → §3 R8, §4.3 별도 element, §5.5/§5.6
- **Codex F6** (P1) → §3 R6, §6 Mongo filter, AC-17
- **Codex F7 / Reviewer F5** (P1/HIGH) → §3 R14, §6 Lettuce group Lua, AC-16
- **Codex F8** (P1) → §3 R3, §6 Redisson owner-Lua
- **Codex F9 / Architect A4** (P1/HIGH) → §3 R12, §7.5, AC-14
- **Codex F10 / Architect A2 / SF3** (P1/HIGH) → §3 R10, §7.1 backend-exception sentinel, AC-4b
- **Codex F11 / Architect A1 / SF7 / Type T9** (P1/HIGH) → §3 R10, §5.4 strict invariant
- **Codex F12** (P1) → §6 Mongo group row 추가
- **Codex F13 / SF7 / Reviewer F7** (P2/HIGH) → §3 R7, §5.1 ThreadLocal fallback 제거
- **Codex F14** (P2) → §7.3 Mono propagation 한정
- **Codex F15 / Architect A6** (P2/MEDIUM) → §8 4 contract bases, AC-1 capability matrix
- **Codex F16 / Type T1+T3+T4** (P2/HIGH) → §4.1 sealed class, §5.2 internal constructor
- **Type T2 / SF2** (HIGH) → §3 R11, §5.3 `ExtendOutcome`, §5.1 detailed API
- **Type T8** (MEDIUM) → §4.3 별도 element 결정
- **Reviewer F1 / Architect A11** (HIGH/LOW) → §0, §11 CLAUDE.md drift
- **Reviewer F6 / SF5** (HIGH) → §3 R5, AC-6b
- **SF1** (HIGH) → §5.1 KDoc 명시 + WARN + metric
- **SF4** (HIGH) → §6 backend exception policy + AC-18
- **SF8** (MEDIUM) → 별도 element 결정으로 자동 해결 (Codex F5 / Type T8)
- **SF9** (MEDIUM) → §6 backend exception policy 추가
- **SF10** (MEDIUM) → AC-4 rate-limited
- **SF11** (MEDIUM) → §5.2 `withReentryDepth` KDoc 명시 (passthrough extend = outermost lease 갱신)
- **SF12** (MEDIUM) → §7.4 detection 로직 명시
- **Architect A3** (HIGH) → §6 watchdog reference 동일 + AC-15
- **Architect A5** (MEDIUM) → §3 R13, §7.4 group reentrant
- **Architect A7** (MEDIUM) → §3 R16, §6 ZK row + AC-20
- **Architect A8** (MEDIUM) → §11 leader-ktor README 노트
- **Architect A9** (LOW) → §11 CHANGELOG + WIP
- **Architect A10** (LOW) → §10.3 마이그레이션

### 미적용 (의도적)

- **Type T5** (MEDIUM, threadId nullable) — sealed class 채택으로 자연스럽게 Real 안에 포함 — 별도 marker 도입 안 함 (overhead).
- **Type T7** (MEDIUM, reentryDepth Int) — sealed class + `withReentryDepth(n)` 의 `require(n >= 0)` 로 충분.
- **Type T10** (LOW, `checkLocked`) — `isLocked()` 로 채택 (§5.1 query API 포함).
- **Type T11** (LOW, withPushed helper) — §5.4 에 `inline fun withPushed` 헬퍼 추가됨.
- **Codex F16 분리** — 위 sealed class 결정에 통합.
- **SF13** (LOW, `error()` 메시지) — 적용 가능하지만 spec 변경 불필요 (구현 시 메시지에 lockName/token 포함).

## Appendix B — Round 2 Review 통합 (Codex 단독)

총 12 finding (P0×1, P1×4, P2×6, P3×1).

### 적용 (12개 모두)

- **R2-F1** (P0) → §0, §2 Goals, §7.1/7.2 failure mode 매트릭스, AC-4b — `LEADERLESS_RUN` → `FAIL_OPEN_RUN` 전역 수정 + `Skipped`/exception × `RETHROW`/`SKIP`/`FAIL_OPEN_RUN` 행렬
- **R2-F2** (P1) → §5.7 — 기존 public `LeaderRunResult { Elected, Skipped }` 재사용, `BackendError` variant 추가 안 함, aspect `try/catch` 로 backend exception 처리, 적용 범위 매트릭스
- **R2-F3** (P1) → §5.2a `LockIdentity` 신규, §5.2b `Real`/`FailOpen` 모두 `identity` 필드, §7.1/7.2 `matchesIdentity` 호출
- **R2-F4** (P1) → §5.3a `ExtendDelegate` interface 신규, AC-15 wording 변경 (reference 동일성 검증)
- **R2-F5** (P1) → §5.3 `ExtendOutcome.Extended.observedExpireAt` (best-effort + backend별 정확도 정책)
- **R2-F6** (P2) → §5.1 `LockExtender` KDoc — Boolean ↔ Detailed 변환 contract 명시
- **R2-F7** (P2) → §5.1 `LockExtender` KDoc — mismatched lockName 처리 명시 (false + WARN / `NotHeld`)
- **R2-F8** (P2) → §5.1 `LockExtender` KDoc — group 의미 명시 (현재 slot 만 갱신)
- **R2-F9** (P2) → §8.1 backend × contract capability matrix 추가 (ZK group 포함)
- **R2-F10** (P2) → AC-18 grep 패턴 확장 (`runCatching|Result\.runCatching|catch\s*\(\s*(Throwable|Exception)`)
- **R2-F11** (P2) → AC-8 Redisson thread-id semantics 명시 (virtual thread / IO hop / mono 시나리오)
- **R2-F12** (P3) → §5.2b `withReentryDepth` KDoc — passthrough extend = outermost lease 갱신 명시

### Round 2 결정 기록

13. **`LockIdentity` 도입** — `(lockName, kind, factoryBeanName, groupParams)` 4-tuple 으로 reentrant dedupe.
14. **`ExtendDelegate` interface 단일 reference** — watchdog 와 LockExtender 가 동일 객체 공유.
15. **`Extended.observedExpireAt`** — `newExpireAt` 명칭 변경 (best-effort 명시), backend별 정확도 정책.
16. **`LeaderRunResult` 변경 없음** — backend exception 은 aspect `try/catch` 로 처리 (public sealed 무변경).
17. **failure mode 행렬 명시적 처리** — `Skipped`/exception × `RETHROW`/`SKIP`/`FAIL_OPEN_RUN` 모든 cell 정의.

---

## Appendix C — Round 3 Review 통합 (Codex 단독)

총 15 finding (P0×0, P1×6, P2×8, P3×1).

### 적용 (P1×6 + P2 다수 + P3 모두)

- **R3-F1** (P1) → §7.1/§7.2 sync/suspend `Skipped × RETHROW` = `null` 명시 + AC-4b 행렬 표 (Skipped/Exception × RETHROW/SKIP/FAIL_OPEN_RUN)
- **R3-F2** (P1) → §7.1/§7.2 sketch `failOpen(identity.lockName)` → `failOpen(identity)` 전역 수정 (4 occurrences)
- **R3-F3** (P1) → §5.7 정책 정리 — `LeaderRunResult` 무변경, suspend default fun 추가 (source/binary 호환), §10.1 source-compat 갱신
- **R3-F4** (P1) → §7.2 suspend sketch `withContext(LeaderElectionInfo + LockHandleElement)` 결합 명시 — 기존 aspect 의 `LeaderElectionInfo` 주입 보존
- **R3-F5** (P1) → §7.1/§7.2 `catch (Throwable)` → `catch (Exception)` — OOM/StackOverflow/LinkageError fail-open 차단
- **R3-F6** (P1) → §6 watchdog interaction sketch `ExtendDelegate` 객체 기반 갱신 (`start(delegate = ...)`)
- **R3-F7** (P2) → §5.2a `GroupParams` "currently maxLeaders only" + 향후 확장 지침
- **R3-F8** (P2) → §5.3a `extendSuspend` default 위험 KDoc + AC-21 (blocking backend override 강제)
- **R3-F9** (P2) → AC-24 — backend 별 transient/non-transient 분류 표 + `BackendErrorClassifier` 헬퍼
- **R3-F10** (P2) → AC-1 wording 수정 — ZK group 실제 존재 반영
- **R3-F11** (P2) → §6 ZK row + §5.3 KDoc — `Extended(Instant.MAX)` 의미 "session-held liveness passthrough" 로 정확화
- **R3-F12** (P2) → §5.5 `LockHandleElement.handle` `internal` 로 낮춤 — 외부 metadata 노출 차단
- **R3-F13** (P2) → AC-22 — AOP CTW weave smoke test (sync/suspend/Mono 실제 woven bean 호출 검증)
- **R3-F14** (P2) → AC-23 — `extendDelegate` reference 검증을 backend 모듈 안 unit test 로 한정 (cross-module access 불가)
- **R3-F15** (P3) → §5.2b `internal` 의미 표현 정확화 (소스 API 차단, reflection boundary 아님)

### Round 3 결정 기록

18. **failure mode 행렬 정합성**: `Skipped × RETHROW` = `null` (contention 정상 흐름), `Exception × RETHROW` 만 throw.
19. **`catch (Exception)` 한정**: fatal error (OOM/SOE/LinkageError) 는 fail-open 분기 대상 아님 — 그대로 propagate.
20. **`SuspendLeaderElector` default fun 추가** — source/binary 호환 (Kotlin default fun 의 caller-side 호환성).
21. **`withContext(LeaderElectionInfo + LockHandleElement)` 결합 push** — 기존 `LeaderElectionInfo` 주입 보존, 신규 element 추가.
22. **`LockHandleElement.handle` internal** — 외부 caller 는 `LockAssert`/`LockExtender` API 만 사용, metadata 직접 노출 차단.
23. **AOP weave smoke test 명시** — Freefair CTW 가 실제 weave 했는지 unit/mock 이상의 통합 테스트 필요.

---

## Appendix D — Round 4 Review 통합 (Codex 단독 — 수렴 검증)

총 7 finding (P0×0, P1×3, P2×3, P3×1).

### 적용 (P1×3 + P2×3 + P3×1)

- **R4-F1** (P1) → §5.7 `runIfLeaderResultSuspend` 시그니처 명시 (`suspend fun <T> runIfLeaderResultSuspend(lockName, action: suspend () -> T): LeaderRunResult<T>`), §7 sketch `meta.options` 인자 제거, T3 task 갱신, §7.6 elector 변경 사항 갱신
- **R4-F2** (P1) → §7.1/§7.2 sketch 재구조 — body exception (`BodyThrownMarker`) vs backend exception 분리, capture invariant failure (`IllegalStateException`) 별도 propagate, `LeaderLockHandleCapture.poll() ?: throw IllegalStateException`
- **R4-F3** (P1) → §5.2a `factoryBeanName` KDoc — branch별 (sync/suspend/group/suspend-group) 다른 factory bean, §7.5b `AdviceMetadata` + `AdviceBranch` enum 신규
- **R4-F4** (P2) → §7.1 sync sketch `return try { ... } catch ...` 구조 명시 (suspend branch 와 대칭)
- **R4-F5** (P2) → §6 `BackendErrorClassifier` API + 8 backend known exception mapping table
- **R4-F6** (P2) → §5.1 `extendActiveLockDetailedSuspend(lockName, lockAtMostFor)` overload 추가 (sync API 와 대칭)
- **R4-F7** (P3) → AC-21 grep 패턴 구체화 — `rg "object\s*:\s*ExtendDelegate"` + `override suspend fun extendSuspend` + `withContext(Dispatchers.IO)`

### Round 4 결정 기록

24. **Body exception ≠ backend exception** — `BodyThrownMarker(cause)` wrapper 로 outer catch 가 구분. business 로직 exception 이 fail-open 분기로 잘못 처리되지 않도록 보장.
25. **Capture invariant failure 는 failureMode 변환 안 함** — `IllegalStateException` 으로 즉시 propagate. spec invariant 위반은 silent fail-open 금지.
26. **`AdviceMetadata` branch별 factoryBeanName** — sync / suspend / Mono 분기마다 정확한 factory bean 사용. reentrant peek 의 LockIdentity 정확성.
27. **`BackendErrorClassifier`** — backend 별 transient/non-transient/fatal 분류 helper, 모든 elector 가 동일 정책.

### 수렴 평가

- Round 1 (63 finding) → 32 P0/P1/HIGH 적용
- Round 2 (12 finding) → 12 적용
- Round 3 (15 finding) → 15 적용
- Round 4 (7 finding) → 7 적용
- **합계 97 finding**, 모두 spec 반영
- Round 4 P0 = 0 → architectural 수렴
- Round 5 추가 review 시 polish/nit 영역으로 진입 예상

---

## Appendix E — Round 5 Review 통합 (Codex 단독 — 수렴 미달)

총 6 finding (P0×0, P1×4, P2×1, P3×1).

### 적용 (P1×4 + P2×1 + P3×1)

- **R5-F1** (P1) → §5.7 `runIfLeaderResultSuspend` default fun `elected: Boolean` flag 패턴 사용 (sync `runIfLeaderResult` LeaderElector.kt:62 와 동일). action 정상 실행 후 null 반환 시 `Skipped` 오분류 차단
- **R5-F2** (P1) → §7.1/§7.2 sketch `proceedProtected()` helper 도입 — elected/contention-fail-open/backend-fail-open 3 분기 모두에서 `BodyThrownMarker` 보호. body exception 이 outer `catch (Exception)` 에 잘못 잡혀 fail-open 재실행되는 footgun 차단
- **R5-F3** (P1) → §7.1/§7.2 `CaptureInvariantException : IllegalStateException` 전용 type 도입. capture 누락은 `throw CaptureInvariantException(...)`, outer catch 에서 `catch (e: CaptureInvariantException) { throw e }` 우선. 일반 `IllegalStateException` (backend 가 throw 가능) 은 backend exception 분기로 흘러감
- **R5-F4** (P1) → §6 `BackendErrorClassifier` SPI 분산 — `leader-core` 에 SPI interface + JDK/공통 분류만, 각 backend module 에 자기 classifier 구현. `CompositeBackendErrorClassifier` 로 chain. `leader-core` build dependency 깨짐 차단
- **R5-F5** (P2) → §10.1 source-compat 만 확정, §10.1b binary-compat 별도 섹션 + AC-25 추가 (`-jvm-default=enable` bytecode 검증)
- **R5-F6** (P3) → §7.1 / §7.2 sketch `meta.resolveLockIdentity(pjp)` → `meta.resolveLockIdentity(AdviceBranch.SYNC)` / `(AdviceBranch.SUSPEND)` 시그니처 일치

### Round 5 결정 기록

28. **suspend default fun `elected` flag** — sync 와 일관된 정확한 Elected/Skipped 분류 (action null return 시 Skipped 오분류 차단).
29. **`proceedProtected()` helper** — elected / contention-fail-open / backend-fail-open 3 분기 모두에서 body exception 을 BodyThrownMarker 로 wrap → outer catch 가 backend exception 과 정확히 구분.
30. **`CaptureInvariantException` 전용 type** — capture 누락 (spec invariant 위반) 과 backend `IllegalStateException` (token mismatch 등 정상 backend 동작) 을 type level 에서 구분.
31. **`BackendErrorClassifier` SPI 분산** — `leader-core` 가 backend exception class 의존하지 않도록 의존성 역전. `CompositeBackendErrorClassifier(backendSpecific, core)` chain.
32. **Binary-compat 진술 약화** — `-jvm-default=enable` bytecode 의존, source-compat 만 확정.

### Round 5 후 수렴 평가

- R1 (63) → R2 (12) → R3 (15) → R4 (7) → R5 (6) — finding 수 단조 감소.
- R5 P0 = 0, P1 4 (모두 architectural correctness — type/SPI/structure).
- R6 dispatch 시 polish/nit 영역 진입 예상 (P1 ≤ 2, P2/P3 위주).

총 review 합계: **103 finding** (Codex 56 + perspective 47), 모두 적용.

---

### 결정 기록 (Round 1 후)

1. **Sealed class `LeaderLockHandle { Real, FailOpen }`** — type design + invariant + sentinel 분리 동시 해결.
2. **별도 `LockHandleElement: CoroutineContext.Element`** — `LeaderElectionInfo` binary-compat 보존.
3. **`ExtendOutcome` sealed result** — `Boolean` API 와 병행, observability + recovery 가시성.
4. **Reentrant dedupe = full lock identity** — Single ↔ Group 간 dedupe 차단.
5. **Capture missing = IllegalStateException** — silent sentinel 금지.
6. **Backend exception fail-open 분기 별도 처리** — sentinel push 보장.
7. **CompletableFuture validator 차단** — v1 미지원 명시.
8. **Lettuce group extend Lua server-side TIME** — #151 lessons 강제.
9. **Redisson owner-guarded Lua** — `RLock.expire` 단순 호출 금지.
10. **Mongo extend filter `expireAt > now`** — 만료 부활 차단.
11. **Watchdog × LockExtender reference 동일** — race-free 보장 + last-write-wins 가시성 metric.
12. **ZK autoExtend=false 강제** — TTL 없는 backend 의 noop 차단.
