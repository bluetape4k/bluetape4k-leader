# Plan — Redis Group slot-token TTL 재설계

**Spec**: `docs/superpowers/specs/2026-05-10-redis-group-slot-token-design.md`
**Issue**: #151
**작성일**: 2026-05-10

---

## Phase 1 — Lettuce primitive (단독 구현, 기존 코드 영향 X)

### T1: `LettuceSlotTokenGroup.kt` 신규 작성
**complexity: high**
**파일**: `leader-redis-lettuce/src/main/kotlin/io/bluetape4k/leader/lettuce/semaphore/LettuceSlotTokenGroup.kt`

내용:
- `class LettuceSlotTokenGroup(connection, lockName, maxLeaders)`
- 3 Lua scripts (`ACQUIRE_SCRIPT`, `RELEASE_SCRIPT`, `STATUS_SCRIPT`) — **server-side `redis.call('TIME')` 사용**
- key prefix: `lg:{lockName}`
- API:
  - `tryAcquire(waitTime: Duration): String?` (sync, spin-poll 50ms)
  - `tryAcquireAsync(waitTime): CompletableFuture<String?>` (recursive delay chain)
  - `tryAcquireSuspending(waitTime): String?` (delay 50ms loop)
  - `release(token, remainingMinLeaseMs: Long)` (sync)
  - `releaseAsync(token, remainingMinLeaseMs): CompletableFuture<Unit>`
  - `releaseSuspending(token, remainingMinLeaseMs)`
  - `availableSlots(): Int`, `activeCount(): Int`
- token = `Base58.randomString(8)`
- `requireNotBlank("lockName")`, `KLogging` companion
- KDoc 한국어 + `## 동작/계약`

### T2: `LettuceSlotTokenGroupTest.kt` 신규
**complexity: medium**
**파일**: `leader-redis-lettuce/src/test/kotlin/io/bluetape4k/leader/lettuce/semaphore/LettuceSlotTokenGroupTest.kt`

시나리오:
- 단일 acquire → token 반환
- maxLeaders=N 동시 acquire 모두 success
- maxLeaders+1 번째 acquire null
- token 으로 release → 즉시 해제
- minLeaseRemaining > 0 release → ZADD XX 로 score 갱신, slot 유지
- expired entry 자동 회수 (`leaseTime` 만료 후 새 acquire 성공)

`AbstractLettuceLeaderTest` 베이스 사용.

---

## Phase 2 — Deprecate `LettuceSemaphore`

### T3: `LettuceSemaphore` / `LettuceSuspendSemaphore` `@Deprecated`
**complexity: low**
**파일**:
- `leader-redis-lettuce/src/main/kotlin/io/bluetape4k/leader/lettuce/semaphore/LettuceSemaphore.kt`
- `leader-redis-lettuce/src/main/kotlin/io/bluetape4k/leader/lettuce/semaphore/LettuceSuspendSemaphore.kt`

`@Deprecated("Replaced by LettuceSlotTokenGroup ...", level = DeprecationLevel.WARNING)`

---

## Phase 3 — Lettuce group elector 교체

### T4: `LettuceLeaderGroupElector` 수정
**complexity: high**
**파일**: `leader-redis-lettuce/src/main/kotlin/io/bluetape4k/leader/lettuce/LettuceLeaderGroupElector.kt`

변경:
- `ConcurrentHashMap<String, LettuceSemaphore>` → `ConcurrentHashMap<String, LettuceSlotTokenGroup>`
- `runIfLeader` / `runAsyncIfLeader`:
  - `tryAcquire(waitTime)` → `String?`
  - `null` → return null
  - **`startedAtNanos = System.nanoTime()` — acquire 성공 후 캡처** (Codex P2)
    - 근거: acquire 전 캡처 시 waitTime 이 minLeaseTime 에서 차감 → fast action 즉시 release 위험
  - try { action() } finally:
    - `remainingMs = remainingMinLeaseTime(startedAtNanos, options.minLeaseTime).inWholeMilliseconds`
    - `release(token, remainingMs)`
- `activeCount(name)` / `availableSlots(name)` → group 의 동일 함수 위임
- 기존 `LettuceSemaphore` import 제거

### T5: `LettuceSuspendLeaderGroupElector` 수정
**complexity: high**
**파일**: `leader-redis-lettuce/src/main/kotlin/io/bluetape4k/leader/lettuce/LettuceSuspendLeaderGroupElector.kt`

T4 와 동일 + suspend 패턴:
```kotlin
withContext(NonCancellable) {
    try {
        val remainingMs = remainingMinLeaseTime(...).inWholeMilliseconds
        slotGroup.releaseSuspending(token, remainingMs)
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) { log.warn(e) { "..." } }
}
```

기존 `runCatching { semaphore.releaseAsync().await() }` (CancellationException 삼킴 버그) 동시 제거.

### T6: Lettuce group 테스트 minLeaseTime + crash recovery 추가
**complexity: medium**
**파일**:
- `leader-redis-lettuce/src/test/kotlin/io/bluetape4k/leader/lettuce/LettuceLeaderGroupElectionTest.kt`
- `leader-redis-lettuce/src/test/kotlin/io/bluetape4k/leader/lettuce/LettuceSuspendLeaderGroupElectorTest.kt`

신규 시나리오 (각 test 클래스):
- minLeaseTime > runtime: caller 즉시 반환 + 다른 client 즉시 acquire 실패
- minLeaseTime 만료 후 다음 acquire 성공
- minLeaseTime == 0 회귀 (즉시 release)
- maxLeaders 동시 점유 + minLease 모두 보유 → maxLeaders+1 실패
- crash recovery: release 미호출 → leaseTime 만료 후 다른 client acquire 성공
- (suspend) 코루틴 취소 + minLeaseTime > 0 → NonCancellable 으로 score 갱신 정확

---

## Phase 4 — Redisson group elector 교체

### T7: `RedissonLeaderGroupElector` 수정
**complexity: high**
**파일**: `leader-redis-redisson/src/main/kotlin/io/bluetape4k/leader/redisson/RedissonLeaderGroupElector.kt`

변경:
- `RSemaphore` → `RPermitExpirableSemaphore`
- key prefix `lg:{lockName}` (충돌 회피)
- `getPermitSemaphore(lockName)`:
  - `redissonClient.getPermitExpirableSemaphore("lg:{$lockName}")`
  - **`semaphore.trySetPermits(maxLeaders)` 멱등 초기화** (Codex P1 — 누락 시 acquire 영구 실패)
- acquire: `tryAcquire(waitTimeMs, leaseTimeMs, MILLISECONDS): String?` (permitId 반환)
- **acquire 성공 후 `startedAtNanos = System.nanoTime()` 캡처** (Codex P2 — waitTime 이 minLease 에서 차감되지 않게)
- release in finally:
  ```kotlin
  val remainingMs = remainingMinLeaseTime(startedAtNanos, options.minLeaseTime).inWholeMilliseconds
  if (remainingMs > 0) {
      semaphore.updateLeaseTime(permitId, remainingMs, TimeUnit.MILLISECONDS)
  } else {
      semaphore.release(permitId)
  }
  ```
  - **API 명: `updateLeaseTime`** (Codex P2 — `tryUpdateLeaseTime` 미존재)
- `activeCount` / `availableSlots`: `availablePermits()` 사용 (RPermitExpirableSemaphore 도 RSemaphore 상속)

### T8: `RedissonSuspendLeaderGroupElector` 수정
**complexity: high**
**파일**: `leader-redis-redisson/src/main/kotlin/io/bluetape4k/leader/redisson/RedissonSuspendLeaderGroupElector.kt`

T7 동일 + suspend:
- `tryAcquireAsync(waitMs, leaseMs, MILLISECONDS).await(): String?`
- `withContext(NonCancellable) { try { ... `updateLeaseTimeAsync(permitId, ...).await()` ... } catch (CancellationException) { throw } catch (Exception) { log.warn } }`
- 기존 `runCatching { ... releaseAsync().await() }` (CancellationException 삼킴) 제거

### T9: Redisson group 테스트 minLeaseTime + crash recovery 추가
**complexity: medium**
**파일**:
- `leader-redis-redisson/src/test/kotlin/io/bluetape4k/leader/redisson/RedissonLeaderGroupElectionTest.kt`
- `leader-redis-redisson/src/test/kotlin/io/bluetape4k/leader/redisson/RedissonSuspendLeaderGroupElectorTest.kt`

T6 와 동일 시나리오 6종.

---

## Phase 5 — Verification

### T10: 전체 빌드 + 테스트
**complexity: low**

```bash
./gradlew :leader-redis-lettuce:test :leader-redis-redisson:test --no-daemon --no-build-cache
./gradlew build -x test -x koverVerify
./gradlew detekt
```

기존 group 테스트 회귀 0 + 새 시나리오 전부 통과.

### T11: README + CLAUDE.md 갱신 (Codex P3)
**complexity: low**
**파일**:
- `leader-redis-lettuce/README.md` + `README.ko.md`
- `leader-redis-redisson/README.md` + `README.ko.md`
- **`CLAUDE.md` (root)** — group 백엔드 모델 변경 (slot-token TTL 위임) 반영

slot-token 모델 + crash recovery + minLeaseTime backend TTL 위임 명시.

### T12: lessons doc
**complexity: low**
**파일**: `docs/lessons/2026-05-10-redis-group-slot-token.md`

L1+: Lua server-side TIME, RPermitExpirableSemaphore 초기화, updateLeaseTime API 명, ZSET sorted set 패턴, 등.

---

## 의존 그래프

```
T1 (Lettuce primitive)
  ↓
T2 (primitive 단위 테스트)
  ↓
T3 (Deprecate old)
  ↓
T4 + T5 (Lettuce electors)
  ↓
T6 (Lettuce group 통합 테스트)
  ↓
T7 + T8 (Redisson electors) — T1~T6 와 독립 (병렬 가능)
  ↓
T9 (Redisson 통합 테스트)
  ↓
T10 + T11 + T12 (검증 + 문서)
```

T1~T6 (Lettuce) 와 T7~T9 (Redisson) 는 **독립 병렬** 가능 (서로 영향 없음).

---

## Task DoD

| ID | 설명 | complexity | 의존 |
|----|------|------------|------|
| T1 | LettuceSlotTokenGroup.kt 신규 | high | - |
| T2 | LettuceSlotTokenGroupTest.kt 신규 | medium | T1 |
| T3 | LettuceSemaphore @Deprecated | low | T1 |
| T4 | LettuceLeaderGroupElector 수정 | high | T1 |
| T5 | LettuceSuspendLeaderGroupElector 수정 | high | T1 |
| T6 | Lettuce group 테스트 추가 | medium | T4 + T5 |
| T7 | RedissonLeaderGroupElector 수정 | high | - |
| T8 | RedissonSuspendLeaderGroupElector 수정 | high | - |
| T9 | Redisson group 테스트 추가 | medium | T7 + T8 |
| T10 | 전체 빌드 + 테스트 | low | T6 + T9 |
| T11 | README 갱신 | low | T10 |
| T12 | lessons doc | low | T10 |
